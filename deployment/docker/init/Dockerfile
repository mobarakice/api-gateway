# One-shot helper for the local Compose stack: dev PKI, random passwords, etcd users/roles.
# etcdctl is a static Go binary copied from the official etcd image (same version as etcd itself).
FROM gcr.io/etcd-development/etcd:v3.7.3 AS etcd
FROM alpine:3.22.2
RUN apk add --no-cache openssl=~3.5
COPY --from=etcd /usr/local/bin/etcdctl /usr/local/bin/etcdctl
COPY init.sh /usr/local/bin/init.sh
ENTRYPOINT ["/bin/sh", "/usr/local/bin/init.sh"]
