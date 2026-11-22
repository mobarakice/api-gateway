#!/bin/sh
# etcd operations for the apigw Kubernetes deployment (docs/operations/etcd-backup-restore.md).
#   etcd-ops bootstrap-auth   create root/gateway/control-plane users + least-privilege roles, enable auth (idempotent)
#   etcd-ops backup           snapshot one healthy member, verify it, upload with checksum, prune old snapshots
#   etcd-ops defrag           defragment members one at a time (leader last), then clear a NOSPACE alarm if any
#   etcd-ops fetch <name|latest> <dir>  download a snapshot and verify its checksum (restore preparation)
#   etcd-ops restore <snapshot> <member> <data-dir>  etcdutl snapshot restore for one member of ETCD_INITIAL_CLUSTER
#   etcd-ops serve-s3         dev-only S3-compatible object store (rclone serve s3) on :9000
# Environment (from ConfigMaps/Secrets; nothing is hard-coded):
#   ETCD_ENDPOINTS (comma-separated https URLs), ETCD_CA_FILE, ETCD_ROOT_PASSWORD_FILE, ETCD_PREFIX (/gw/<env>/),
#   ETCD_GATEWAY_PASSWORD_FILE, ETCD_CONTROL_PLANE_PASSWORD_FILE, ETCD_INITIAL_CLUSTER, ETCD_CLUSTER_TOKEN,
#   RESTORE_BUMP_REVISION (default 1e9, must exceed revisions written after the snapshot),
#   BACKUP_REMOTE (rclone remote:path, e.g. backup:apigw-etcd/prod), BACKUP_RETENTION (rclone age, default 14d),
#   RCLONE_CONFIG_BACKUP_* (rclone remote "backup" definition; credentials from a Secret).
set -eu
umask 077

log() { printf '{"ts":"%s","logger":"etcd-ops","msg":"%s"}\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "error: $*"; exit 1; }
need() { eval "v=\${$1:-}"; [ -n "$v" ] || die "$1 is required"; }

ctl() {
  etcdctl --endpoints="$ETCD_ENDPOINTS" --cacert="$ETCD_CA_FILE" --command-timeout=10s --dial-timeout=5s "$@"
}
root_ctl() { ctl --user "root:$(cat "$ETCD_ROOT_PASSWORD_FILE")" "$@"; }

wait_healthy() {
  i=0
  until ctl endpoint health >/dev/null 2>&1 || root_ctl endpoint health >/dev/null 2>&1; do
    i=$((i + 1)); [ "$i" -gt 120 ] && die "etcd not healthy after 120s"
    sleep 1
  done
}

bootstrap_auth() {
  need ETCD_ENDPOINTS; need ETCD_CA_FILE; need ETCD_ROOT_PASSWORD_FILE; need ETCD_PREFIX
  need ETCD_GATEWAY_PASSWORD_FILE; need ETCD_CONTROL_PLANE_PASSWORD_FILE
  case "$ETCD_PREFIX" in /gw/*/) ;; *) die "ETCD_PREFIX must look like /gw/<env>/" ;; esac
  wait_healthy
  if root_ctl auth status 2>/dev/null | grep -q 'Authentication Status: true'; then
    log "auth already enabled; nothing to do"
    return
  fi
  # Re-runnable until auth is enabled: "already exists" errors are expected on a retry.
  ctl user add root --new-user-password="$(cat "$ETCD_ROOT_PASSWORD_FILE")" >/dev/null 2>&1 || true
  ctl user grant-role root root >/dev/null
  ctl role add gateway-read >/dev/null 2>&1 || true
  ctl role grant-permission gateway-read --prefix=true read "$ETCD_PREFIX" >/dev/null
  ctl role grant-permission gateway-read --prefix=true readwrite "${ETCD_PREFIX}nodes/" >/dev/null
  ctl user add gateway --new-user-password="$(cat "$ETCD_GATEWAY_PASSWORD_FILE")" >/dev/null 2>&1 || true
  ctl user grant-role gateway gateway-read >/dev/null
  ctl role add control-plane >/dev/null 2>&1 || true
  ctl role grant-permission control-plane --prefix=true read "$ETCD_PREFIX" >/dev/null
  for sub in meta/ routes/ services/ upstreams/ global-plugins/ consumers/ credentials/ plugin-configs/ policies/ \
      certificates/ changesets/ history/ audit/; do
    ctl role grant-permission control-plane --prefix=true readwrite "$ETCD_PREFIX$sub" >/dev/null
  done
  ctl user add control-plane --new-user-password="$(cat "$ETCD_CONTROL_PLANE_PASSWORD_FILE")" >/dev/null 2>&1 || true
  ctl user grant-role control-plane control-plane >/dev/null
  ctl auth enable >/dev/null
  log "auth enabled (users root, gateway, control-plane; prefix $ETCD_PREFIX)"
}

backup() {
  need ETCD_ENDPOINTS; need ETCD_CA_FILE; need ETCD_ROOT_PASSWORD_FILE; need BACKUP_REMOTE
  dir=${BACKUP_DIR:-/backup}
  name="etcd-$(date -u +%Y%m%dT%H%M%SZ).db"
  # Snapshot from the first healthy member (a snapshot is taken from a single member by design).
  ep=""
  for e in $(echo "$ETCD_ENDPOINTS" | tr ',' ' '); do
    if etcdctl --endpoints="$e" --cacert="$ETCD_CA_FILE" --user "root:$(cat "$ETCD_ROOT_PASSWORD_FILE")" \
        --command-timeout=5s endpoint health >/dev/null 2>&1; then ep=$e; break; fi
  done
  [ -n "$ep" ] || die "no healthy etcd member"
  etcdctl --endpoints="$ep" --cacert="$ETCD_CA_FILE" --user "root:$(cat "$ETCD_ROOT_PASSWORD_FILE")" \
    --command-timeout=300s snapshot save "$dir/$name" >/dev/null
  status=$(etcdutl snapshot status "$dir/$name" -w json)
  (cd "$dir" && sha256sum "$name" > "$name.sha256")
  rclone mkdir "$BACKUP_REMOTE" 2>/dev/null || true   # no-op on S3 paths; creates the bucket on the dev store
  rclone copyto --s3-no-check-bucket "$dir/$name" "$BACKUP_REMOTE/$name"
  rclone copyto --s3-no-check-bucket "$dir/$name.sha256" "$BACKUP_REMOTE/$name.sha256"
  # Verify what landed in the store, not just what was sent.
  rclone cat "$BACKUP_REMOTE/$name" | sha256sum | cut -d' ' -f1 | grep -qx "$(cut -d' ' -f1 "$dir/$name.sha256")" \
    || die "uploaded snapshot checksum mismatch"
  rm -f "$dir/$name" "$dir/$name.sha256"
  rclone delete --min-age "${BACKUP_RETENTION:-14d}" --include 'etcd-*.db*' "$BACKUP_REMOTE"
  log "snapshot $name from $ep uploaded to $BACKUP_REMOTE ($status)"
}

defrag() {
  need ETCD_ENDPOINTS; need ETCD_CA_FILE; need ETCD_ROOT_PASSWORD_FILE
  wait_healthy
  leader=$(root_ctl endpoint status -w table --cluster 2>/dev/null | awk -F'|' '$6 ~ /true/ {gsub(/ /,"",$2); print $2}')
  for e in $(echo "$ETCD_ENDPOINTS" | tr ',' ' '); do
    [ "$e" = "$leader" ] && continue
    root_ctl --endpoints="$e" --command-timeout=120s defrag >/dev/null && log "defragmented $e"
    sleep 5
  done
  if [ -n "$leader" ]; then
    root_ctl --endpoints="$leader" --command-timeout=120s defrag >/dev/null && log "defragmented $leader (leader)"
  fi
  if root_ctl alarm list | grep -q NOSPACE; then
    root_ctl alarm disarm >/dev/null && log "NOSPACE alarm disarmed after defrag"
  fi
}

fetch() {
  need BACKUP_REMOTE
  [ $# -eq 2 ] || die "usage: etcd-ops fetch <name|latest> <dir>"
  name=$1; dir=$2
  if [ "$name" = latest ]; then
    name=$(rclone lsf --include 'etcd-*.db' "$BACKUP_REMOTE" | sort | tail -1)
    [ -n "$name" ] || die "no snapshots in $BACKUP_REMOTE"
  fi
  rclone copyto "$BACKUP_REMOTE/$name" "$dir/snapshot.db"
  rclone cat "$BACKUP_REMOTE/$name.sha256" | cut -d' ' -f1 > "$dir/expected.sha256"
  (cd "$dir" && sha256sum snapshot.db | cut -d' ' -f1 | grep -qxf expected.sha256) || die "checksum mismatch for $name"
  etcdutl snapshot status "$dir/snapshot.db" -w table
  log "fetched $name to $dir/snapshot.db (checksum ok)"
}

restore() {
  need ETCD_INITIAL_CLUSTER; need ETCD_CLUSTER_TOKEN
  [ $# -eq 3 ] || die "usage: etcd-ops restore <snapshot> <member> <data-dir>"
  snap=$1; member=$2; data=$3
  peer=$(echo "$ETCD_INITIAL_CLUSTER" | tr ',' '\n' | sed -n "s|^$member=||p")
  [ -n "$peer" ] || die "member $member not in ETCD_INITIAL_CLUSTER"
  [ ! -e "$data/member" ] || die "$data already holds a member directory; move it aside first"
  # Gateways have applied revisions newer than the snapshot. Without a bump their watches (from their applied
  # revision + 1) would wait for revisions the restored cluster has not reached and silently keep the post-backup
  # config. Bumping well past any revision seen and marking it compacted makes every watcher get a compaction
  # error and resync the restored state (ADR 0014 resync path).
  etcdutl snapshot restore "$snap" --name "$member" --data-dir "$data" \
    --initial-cluster "$ETCD_INITIAL_CLUSTER" --initial-cluster-token "$ETCD_CLUSTER_TOKEN" \
    --initial-advertise-peer-urls "$peer" \
    --bump-revision "${RESTORE_BUMP_REVISION:-1000000000}" --mark-compacted >/dev/null
  log "restored $snap into $data for $member (revision bumped by ${RESTORE_BUMP_REVISION:-1000000000}, marked compacted)"
}

serve_s3() {
  need S3_ACCESS_KEY_FILE; need S3_SECRET_KEY_FILE
  exec rclone serve s3 --addr :9000 --auth-key "$(cat "$S3_ACCESS_KEY_FILE"),$(cat "$S3_SECRET_KEY_FILE")" \
    --vfs-cache-mode off "${S3_ROOT:-/data}"
}

cmd=${1:-}; [ $# -gt 0 ] && shift
case "$cmd" in
  bootstrap-auth) bootstrap_auth ;;
  backup) backup ;;
  defrag) defrag ;;
  fetch) fetch "$@" ;;
  restore) restore "$@" ;;
  serve-s3) serve_s3 ;;
  *) echo "usage: etcd-ops bootstrap-auth|backup|defrag|fetch|restore|serve-s3" >&2; exit 2 ;;
esac
