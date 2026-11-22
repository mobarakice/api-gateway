#!/bin/sh
# Seeds the dev environment through the Admin API: settings (allow the cluster's service CIDR as upstream),
# the echo upstream + service, and route /echo/{rest}. Idempotent enough for rehearsals (re-publishes).
set -eu
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
SVC_CIDR=${SVC_CIDR:-10.96.0.0/12}
cp_forward
cs=$(admin editor POST changesets '{"title":"dev seed"}' | json 'd["id"]')
admin admin PUT "settings?changeset=$cs" "{\"spec\":{\"upstreamAllowCidrs\":[\"$SVC_CIDR\"]}}" >/dev/null
admin editor PUT "upstreams/echo?changeset=$cs" \
  "{\"spec\":{\"targets\":[{\"host\":\"echo.$NS.svc.cluster.local\",\"port\":80}]}}" >/dev/null
admin editor PUT "services/echo?changeset=$cs" '{"spec":{"upstreamId":"echo"}}' >/dev/null
admin editor PUT "routes/echo?changeset=$cs" '{"spec":{"path":"/echo","match":"prefix","serviceId":"echo"}}' >/dev/null
admin publisher POST "changesets/$cs/publish" >/dev/null
log "seeded $NS: route /echo -> echo.$NS.svc"
