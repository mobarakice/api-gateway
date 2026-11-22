# etcd backup and restore runbook

Applies to the etcd StatefulSet from `deployment/kubernetes` (ADR 0024).
- Every command runs in the environment's namespace: `export NS=apigw-prod` (`apigw-dev` for the dev overlay).
- The `etcd-ops` commands come from the `apigw/etcd-tools` image (`deployment/docker/etcd-tools/etcd-ops.sh`).
- The full procedure is rehearsed end to end by `deployment/kubernetes/scripts/backup-restore.sh`. The latest results are in
  `PROGRESS.md` (M9).

**While you work through this runbook, the gateways keep serving.** They keep their current snapshot in memory, and their
last-known-good copy is on disk. No request path touches etcd (invariants 2 and 6). What you lose while etcd is down is
**publishing**: the Admin API returns errors for writes until etcd is back.

## Backups
- The CronJob `etcd-backup` runs hourly at :17.
- Each run snapshots the first healthy member, runs `etcdutl snapshot status` on the file, and uploads `etcd-<UTC timestamp>.db` and
  `.db.sha256` to `BACKUP_REMOTE` (an rclone remote, an S3-compatible store).
- It then **re-reads the uploaded object and compares checksums**, and deletes snapshots older than `BACKUP_RETENTION`
  (dev 2 d, staging 14 d, prod 30 d).
- A snapshot contains the whole keyspace: config, `changesets/`, `history/`, `audit/`, and the users and roles. Credentials are stored as salted hashes.
  Still, treat the bucket as sensitive: encrypt it at rest, use a dedicated write-only CronJob credential, and keep read access separate.

```bash
kubectl -n $NS create job backup-now-$(date +%s) --from=cronjob/etcd-backup   # on-demand backup (before risky work)
kubectl -n $NS logs job/backup-now-...                                        # "snapshot etcd-... uploaded"
```
**Monitor:** alert when the CronJob's last successful run is older than 2 hours (`kube_cronjob_status_last_successful_time` from
kube-state-metrics), and on `EtcdDatabaseNearQuota`.

## Decide what you are restoring from
| Situation | Do this |
|---|---|
| One member lost (node gone, volume corrupt); quorum intact | **Do not restore.** Replace the member: [Replace one member](#replace-one-member) |
| Two or three members lost, or all data corrupt | [Full restore](#full-restore) from the latest good snapshot |
| A bad publish | **Do not restore.** Use the Admin API rollback (`POST /admin/v1/history/{seq}/rollback`). It is a normal, audited publish |

A restore rewinds **everything** to the snapshot. Publishes, API keys and audit entries made after it are lost, and
gateways converge back to the snapshot's state. Record what changed since the snapshot (`GET /admin/v1/history`) before
you start, if the cluster is still readable.

## Full restore
Time on the dev kind cluster: see PROGRESS.md. Most of the time goes to pulling volumes and members starting.

1. **Freeze publishing.** Scale the control plane to 0 so nobody publishes into a half-restored cluster:
   `kubectl -n $NS scale deployment/control-plane --replicas=0`.
2. **Pick the snapshot.** List them with `rclone lsf backup:<bucket>/<env>` from an etcd-tools pod, or use `latest`. `fetch`
   verifies the checksum and prints `snapshot status` (revision, keys, size).
3. **Stop etcd and replace the volumes:**
   ```bash
   kubectl -n $NS delete statefulset etcd            # pods stop; PVCs stay
   kubectl -n $NS delete pvc data-etcd-0 data-etcd-1 data-etcd-2   # or keep them aside: rename/snapshot at the storage layer
   ```
4. **Restore each member** onto a fresh PVC named `data-etcd-N`, so the StatefulSet adopts it.
   - Run one Job per member that runs:
     ```
     etcd-ops fetch <snapshot|latest> /restore
     etcd-ops restore /restore/snapshot.db etcd-N /var/lib/etcd/data
     ```
   - Mount the PVC at `/var/lib/etcd`.
   - Give the three Jobs required anti-affinity on `kubernetes.io/hostname`, so the volumes land on different nodes the way etcd needs.
   - `deployment/kubernetes/scripts/backup-restore.sh` contains exactly these manifests.
   - `restore` uses `ETCD_INITIAL_CLUSTER` and `ETCD_CLUSTER_TOKEN` from `etcd-ops-env`, so the members' identities match the StatefulSet.
   - It passes `--bump-revision 1000000000 --mark-compacted`. **Do not drop this.** Gateways have applied revisions newer than the
     snapshot. Without the bump, their watches would wait for revisions the restored cluster never reaches, and they would
     keep serving the post-snapshot config without reporting an error.
   - The bump makes every revision they hold "compacted", so they resync and converge.
   - Set `RESTORE_BUMP_REVISION` higher only if more than 10⁹ revisions were written after the snapshot.
5. **Start etcd:** `kubectl apply -k deployment/kubernetes/overlays/<env>`, then `kubectl -n $NS rollout status statefulset/etcd`.
   Re-applying the overlay also brings the control plane back. That is safe: it reports ready only once etcd is readable.
   Hold off publishing until step 7 passes.
   The members start from the restored data. `--initial-cluster-state` is ignored once a data directory exists.
   Auth, users and roles come back with the snapshot, so `etcd-bootstrap-auth` stays a no-op.
6. **Verify etcd:**
   ```bash
   etcdctl --endpoints=$ETCD_ENDPOINTS --cacert=... --user root:... endpoint status -w table   # 3 members, same revision (> 1e9)
   etcdctl ... endpoint health
   ```
7. **Verify the gateways.**
   - `gw_config_resyncs_total` increases on every gateway, and `gw_config_applied_revision` jumps above 10⁹.
   - `GatewayBehindOnRevision` and `GatewayConfigStale` clear.
   - Spot-check that a route from after the snapshot is gone and one from before it is still served.
   - Gateways also re-register their `nodes/` heartbeat. A lease from before the restore is no longer known, so the worker grants a new one.
   - Gateways and the control plane re-authenticate on their own. A simple auth token (`<token>.<raftIndex>`) is accepted only
     once the member's applied raft index reaches the index it was issued at. A restored cluster restarts at a low raft index,
     so requests carrying an old token **hang** until their timeout instead of being refused. The etcd client drops its token
     and pooled connections on the first timeout and authenticates again. Expect about one request timeout (3 s by default)
     before convergence. In the kind rehearsal, gateways resynced about 1 s after etcd was ready.
8. **Unfreeze:** resume publishing. Check `GET /admin/v1/gateways`: all gateways are listed, none is `behind`.
   Re-apply the changes recorded in the decision step if they are still wanted.

## Replace one member
Use this when quorum is intact (2 of 3 healthy) and one member's data is gone or corrupt. Don't restore a snapshot onto one member alone.
1. Remove the member from the cluster:
   ```bash
   etcdctl member list -w table
   etcdctl member remove <id of etcd-N>
   ```
2. Delete its pod and PVC:
   ```bash
   kubectl -n $NS delete pvc data-etcd-N
   kubectl -n $NS delete pod etcd-N
   ```
   The StatefulSet recreates the PVC when the pod comes back.
3. Before the pod starts, add the member back:
   ```bash
   etcdctl member add etcd-N --peer-urls=https://etcd-N.etcd:2380
   ```
4. Start it with `--initial-cluster-state=existing`. Patch the pod's env with `ETCD_INITIAL_CLUSTER_STATE=existing` for this start
   only, then revert the patch.
5. Watch `etcdctl endpoint status` until its revision catches up.

## Defragmentation and quota
- `etcd-defrag` runs weekly. Run it now with `kubectl -n $NS create job defrag-now --from=cronjob/etcd-defrag`.
- If `EtcdDatabaseNearQuota` fires or a NOSPACE alarm is raised:
  1. Check retention: `CP_HISTORY_LIMIT`, `CP_AUDIT_WINDOW` (ADR 0021).
  2. Run defrag; the job disarms the alarm afterwards.
  3. Raise `ETCD_QUOTA_BACKEND_BYTES` in the overlay only if the data really needs it.

## Rehearse
Rehearse at least quarterly, and after every etcd upgrade, on a dev cluster:
`deployment/kubernetes/scripts/backup-restore.sh`. It performs a backup, a post-backup publish, total loss of the StatefulSet and all
volumes under load, the restore, and verification. It fails on any failed gateway request during the outage.
