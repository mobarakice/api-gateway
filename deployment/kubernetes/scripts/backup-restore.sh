#!/bin/sh
# etcd backup + restore rehearsal (docs/operations/etcd-backup-restore.md, scenario "total loss of the cluster"):
#  1. publish route /before, take a backup with the CronJob's template, publish route /after (post-backup change)
#  2. keep load running through the gateway for the whole rehearsal (gateways must keep serving with etcd gone)
#  3. destroy etcd: delete the StatefulSet and every member's volume
#  4. restore: fresh volumes, per-member `etcd-ops restore` of the latest snapshot (revision bumped), re-apply
#  5. verify: cluster healthy, Admin API and gateways show the backed-up state (/before 200, /after 404),
#     gateways resynced, zero failed requests during the outage
#   NS=apigw-dev OVERLAY=deployment/kubernetes/overlays/dev backup-restore.sh
set -eu
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
OVERLAY=${OVERLAY:-$HERE/../overlays/dev}
TS=$(date +%s)
cp_forward
gw_forward
trap 'kill $PF_PID $GW_PF 2>/dev/null || true' EXIT

publish_routes "rehearsal: before backup" "before:/before" >/dev/null
i=0; until [ "$(gw_code /before)" = 200 ]; do i=$((i+1)); [ $i -gt 30 ] && die "/before not served"; sleep 1; done
log "published /before; gateways serve it"

kubectl -n "$NS" create job "backup-$TS" --from=cronjob/etcd-backup >/dev/null
kubectl -n "$NS" wait --for=condition=Complete --timeout=300s "job/backup-$TS" >/dev/null || {
  kubectl -n "$NS" logs "job/backup-$TS"; die "backup job"; }
kubectl -n "$NS" logs "job/backup-$TS" | tail -1
publish_routes "rehearsal: after backup" "after:/after" >/dev/null
i=0; until [ "$(gw_code /after)" = 200 ]; do i=$((i+1)); [ $i -gt 30 ] && die "/after not served"; sleep 1; done
log "published /after (not in the backup); gateways serve it"

k6_start "outage-$TS" 100 "${OUTAGE_LOAD_DURATION:-240s}" /echo/outage

log "DISASTER: deleting the etcd StatefulSet and all member volumes"
t0=$(date +%s)
kill $PF_PID 2>/dev/null || true
kubectl -n "$NS" scale deployment/control-plane --replicas=0 >/dev/null   # runbook step 1: freeze publishing
kubectl -n "$NS" delete statefulset etcd --wait=true >/dev/null
kubectl -n "$NS" wait --for=delete pod -l app.kubernetes.io/name=etcd --timeout=120s >/dev/null 2>&1 || true
kubectl -n "$NS" delete pvc data-etcd-0 data-etcd-1 data-etcd-2 --wait=true >/dev/null
[ "$(gw_code /echo/during-outage)" = 200 ] || die "gateway stopped serving while etcd is gone"
log "etcd gone; gateway still serves /echo (200)"

SIZE=$(kubectl kustomize "$OVERLAY" | python3 -c '
import sys,re
t=sys.stdin.read(); m=re.search(r"volumeClaimTemplates:.*?storage: (\S+)", t, re.S); print(m.group(1))')
for n in 0 1 2; do
  kubectl -n "$NS" apply -f - >/dev/null <<YAML
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: data-etcd-$n
  labels: {app.kubernetes.io/name: etcd}
spec:
  accessModes: [ReadWriteOnce]
  resources: {requests: {storage: $SIZE}}
---
apiVersion: batch/v1
kind: Job
metadata: {name: restore-etcd-$n-$TS}
spec:
  backoffLimit: 0
  template:
    metadata: {labels: {app.kubernetes.io/name: etcd-ops, apigw/etcd-restore: "true"}}
    spec:
      restartPolicy: Never
      automountServiceAccountToken: false
      serviceAccountName: etcd-ops
      securityContext: {runAsNonRoot: true, runAsUser: 10001, runAsGroup: 10001, fsGroup: 10001,
                        seccompProfile: {type: RuntimeDefault}}
      # One member's volume per node, like the StatefulSet (its pods then follow their volumes).
      affinity:
        podAntiAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
            - topologyKey: kubernetes.io/hostname
              labelSelector: {matchLabels: {apigw/etcd-restore: "true"}}
      containers:
        - name: restore
          image: apigw/etcd-tools:0.9.0
          imagePullPolicy: IfNotPresent
          command: [sh, -c, 'etcd-ops fetch latest /restore && etcd-ops restore /restore/snapshot.db etcd-$n /var/lib/etcd/data']
          envFrom:
            - configMapRef: {name: $(kubectl -n "$NS" get cm -o name | grep etcd-ops-env | tail -1 | cut -d/ -f2)}
            - secretRef: {name: etcd-backup-s3}
          env: [{name: HOME, value: /tmp}]
          securityContext: {allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: {drop: [ALL]}}
          volumeMounts: [{name: data, mountPath: /var/lib/etcd}, {name: restore, mountPath: /restore},
                         {name: tmp, mountPath: /tmp}]
      volumes:
        - {name: data, persistentVolumeClaim: {claimName: data-etcd-$n}}
        - {name: restore, emptyDir: {}}
        - {name: tmp, emptyDir: {}}
YAML
done
for n in 0 1 2; do
  kubectl -n "$NS" wait --for=condition=Complete --timeout=300s "job/restore-etcd-$n-$TS" >/dev/null || {
    kubectl -n "$NS" logs "job/restore-etcd-$n-$TS"; die "restore etcd-$n"; }
  kubectl -n "$NS" logs "job/restore-etcd-$n-$TS" | tail -1
done
kubectl -n "$NS" delete job -l apigw/etcd-restore=true >/dev/null 2>&1 || true
kubectl -n "$NS" delete job "restore-etcd-0-$TS" "restore-etcd-1-$TS" "restore-etcd-2-$TS" --ignore-not-found >/dev/null
kubectl apply -k "$OVERLAY" >/dev/null   # recreates etcd; also restores the control plane's replicas
kubectl -n "$NS" rollout status statefulset/etcd --timeout=300s >/dev/null
kubectl -n "$NS" rollout status deployment/control-plane --timeout=300s >/dev/null
cp_forward
trap 'kill $PF_PID $GW_PF 2>/dev/null || true' EXIT
log "etcd restored and ready ($(( $(date +%s) - t0 ))s after the disaster)"

[ "$(admin_code viewer routes/before)" = 200 ] || die "Admin API lost /before"
[ "$(admin_code viewer routes/after)" = 404 ] || die "Admin API still has /after (not restored from backup?)"
i=0; until [ "$(gw_code /after)" = 404 ]; do i=$((i+1)); [ $i -gt 60 ] && die "gateways did not resync to the restored state"; sleep 1; done
[ "$(gw_code /before)" = 200 ] || die "gateway lost /before"
log "gateways resynced to the restored state $(( $(date +%s) - t0 ))s after the disaster: /before 200, /after 404"
publish_routes "rehearsal: after restore" "restored:/restored" >/dev/null
i=0; until [ "$(gw_code /restored)" = 200 ]; do i=$((i+1)); [ $i -gt 30 ] && die "publish after restore not served"; sleep 1; done
log "publish after restore reaches the gateways"

k6_finish "outage-$TS" || die "requests failed while etcd was down/restored (see $LOCAL/outage-$TS.txt)"
log "PASS: backup, total loss, restore; zero failed requests through the gateway during the outage"
