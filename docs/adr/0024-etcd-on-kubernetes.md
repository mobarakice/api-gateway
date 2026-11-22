# 0024 etcd on Kubernetes: a dedicated StatefulSet, operated by Jobs

- Status: Accepted (2026-11-22)

## Context
The gateway's configuration lives in a dedicated 3-member etcd cluster with TLS and auth (spec §13; never the
Kubernetes cluster's own etcd). M9 needs it deployable with every overlay, backed up to object storage, restorable,
defragmented, and protected by PDBs and anti-affinity.

## Options
1. **StatefulSet** in the apigw namespace, static bootstrap (`--initial-cluster`), with operations (auth bootstrap,
   backup, defrag, restore) as Jobs/CronJobs running a small `etcd-tools` image.
2. **An etcd operator** (etcd-io/etcd-operator, Aenix etcd-operator, ...). Membership changes and TLS rotation are
   automated, but this adds CRDs, a controller with cluster-wide RBAC, and its own upgrade cycle. Projects are young
   and their backup support varies.
3. **Managed etcd**, or etcd on VMs outside the cluster. That is the best isolation, but no portable manifest
   exists for it, and the cost of running it lands on the user.

## Decision
Option 1 for the shipped manifests. The gateway only needs `GW_ETCD_ENDPOINTS` + CA + credentials, so options 2 and
3 remain drop-in replacements: delete `base/etcd` in an overlay and point the endpoints elsewhere.
- **Bootstrap:**
  - `podManagementPolicy: Parallel`, so the members form quorum together.
  - A headless Service with `publishNotReadyAddresses` gives stable peer names `etcd-N.etcd` before readiness.
- **TLS:**
  - The client and peer listeners share a server certificate (SANs `*.etcd[.ns[.svc[.cluster.local]]]`).
  - Peer client-cert auth is on.
  - Clients authenticate with passwords (`--auth-token=simple`), with least-privilege roles, exactly as in Compose
    and the Testcontainers suites.
- **Auth:** the Job `etcd-bootstrap-auth` creates the users and roles and then enables auth. It is idempotent, so applying
  it again is safe.
- **Placement and disruption:**
  - Required pod anti-affinity: one member per node. Zone spread is ScheduleAnyway.
  - A PDB with `minAvailable: 2` keeps quorum during voluntary disruptions.
  - Overlays set an SSD `storageClassName`.
- **Compaction and defrag:**
  - Periodic auto-compaction keeps 1 h of history. Gateways resync on compaction (ADR 0014).
  - The CronJob `etcd-defrag` runs weekly, one member at a time with the leader last, and clears a NOSPACE alarm.
- **Backup:**
  - The CronJob `etcd-backup` runs hourly: `etcdctl snapshot save` from one healthy member, `etcdutl snapshot status`,
    upload through rclone to any S3-compatible store, verification of the uploaded object's sha256, and pruning by age.
  - Credentials for the backup store come from a Secret, so there is no cloud SDK and no hard-coded host.
- **Restore:** see the runbook in `docs/operations/etcd-backup-restore.md`. Each member gets a fresh volume and runs
  `etcdutl snapshot restore` with `--bump-revision` and `--mark-compacted`, then the StatefulSet is re-applied.
  - The bump is required. A restored cluster's revision is lower than the revisions the gateways have already applied.
    Without the bump, the gateways' watches would wait silently and keep serving post-backup config.
  - With the bump, every watcher gets a compaction error and resyncs.
  - Found during the M9 rehearsal design and verified by it.

## Impact
- **Performance:** none on the request path (invariant 2). etcd runs with 500m CPU and 512 Mi requested, and up to
  2 Gi of memory.
- **Memory:** bounded by `--quota-backend-bytes` (2 GiB, alerted at 80%).
- **Native Image:** not applicable. etcd and the tools are official Go binaries.
- **Operational:**
  - Members are static. Scaling beyond 3 or replacing a member's identity follows the etcd runtime-reconfiguration
    procedure (documented); there is no operator.
  - TLS rotation means replacing the Secret and restarting the members one at a time (PDB-safe).
  - The rehearsal (`deployment/kubernetes/scripts/backup-restore.sh`) runs the full-loss restore end to end.
