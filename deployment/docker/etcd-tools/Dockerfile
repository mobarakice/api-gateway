# etcd operations image for Kubernetes Jobs/CronJobs: auth bootstrap, snapshot backup to object storage, defrag,
# restore preparation. etcdctl/etcdutl are copied from the official etcd image (same version as the cluster);
# rclone (official image, Alpine-based) uploads snapshots to any S3-compatible store and serves the dev store.
#   docker build -t apigw/etcd-tools:<version> deployment/docker/etcd-tools
FROM gcr.io/etcd-development/etcd:v3.7.3 AS etcd
FROM rclone/rclone:1.76.0@sha256:7cbeec24a44f206067597c0368be4de90ae19a64d5113c7d1938bd737a81e466
COPY --from=etcd /usr/local/bin/etcdctl /usr/local/bin/etcdutl /usr/local/bin/
COPY --chmod=0555 etcd-ops.sh /usr/local/bin/etcd-ops
USER 10001:10001
ENTRYPOINT ["/usr/local/bin/etcd-ops"]
