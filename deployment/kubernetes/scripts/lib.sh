# Shared helpers for the dev rehearsal scripts (sourced). Admin API through a kubectl port-forward, dev tokens.
NS=${NS:-apigw-dev}
HERE=$(cd "$(dirname "$0")" && pwd)
LOCAL="$HERE/../.local/$NS"
CP_PORT=${CP_PORT:-19443}
log() { printf '%s %s\n' "$(date -u +%H:%M:%S)" "$*"; }
die() { log "FAIL: $*" >&2; exit 1; }   # stderr: also visible when called inside $(...)

cp_forward() {
  kubectl -n "$NS" port-forward svc/control-plane "$CP_PORT:9443" >/dev/null 2>&1 &
  PF_PID=$!
  trap 'kill $PF_PID 2>/dev/null || true' EXIT
  i=0; until curl -s --cacert "$LOCAL/ca.pem" "https://localhost:$CP_PORT/admin/v1/health" >/dev/null 2>&1; do
    i=$((i+1)); [ $i -gt 60 ] && die "control-plane port-forward"; sleep 1; done
}
# admin <role> <method> <path> [json-body]  -> prints body; fails on non-2xx. IF_MATCH=<etag> adds If-Match.
admin() {
  role=$1; method=$2; path=$3; body=${4:-}
  tok=$("$HERE/mint-token.sh" "$role" "$NS")
  out=$(mktemp)
  if [ -n "$body" ]; then
    code=$(curl -s -o "$out" -w '%{http_code}' --cacert "$LOCAL/ca.pem" -X "$method" -H "authorization: Bearer $tok" \
      ${IF_MATCH:+-H "if-match: $IF_MATCH"} \
      -H 'content-type: application/json' --data "$body" "https://localhost:$CP_PORT/admin/v1/$path")
  else
    code=$(curl -s -o "$out" -w '%{http_code}' --cacert "$LOCAL/ca.pem" -X "$method" -H "authorization: Bearer $tok" \
      "https://localhost:$CP_PORT/admin/v1/$path")
  fi
  cat "$out"; rm -f "$out"
  case "$code" in 2*) ;; *) echo >&2; die "$method $path -> $code" ;; esac
}
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
# etag_of <path>: the live object's ETag, empty if it does not exist
etag_of() {
  curl -s -o /dev/null -D - --cacert "$LOCAL/ca.pem" -H "authorization: Bearer $("$HERE/mint-token.sh" viewer "$NS")" \
    "https://localhost:$CP_PORT/admin/v1/$1" | awk 'tolower($1)=="etag:" {sub(/\r$/, "", $2); print $2}'
}
# publish_routes <title> <route-id:path>...  one change set, published (existing routes are updated via If-Match)
publish_routes() {
  title=$1; shift
  cs=$(admin editor POST changesets "{\"title\":\"$title\"}" | json 'd["id"]')
  for r in "$@"; do
    IF_MATCH=$(etag_of "routes/${r%%:*}") \
      admin editor PUT "routes/${r%%:*}?changeset=$cs" "{\"spec\":{\"path\":\"${r#*:}\",\"serviceId\":\"echo\"}}" >/dev/null
  done
  admin publisher POST "changesets/$cs/publish" | json 'd.get("revision", d.get("seq"))'
}
K6_IMAGE=${K6_IMAGE:-grafana/k6:2.4.0}
# k6_start <name> <rate/s> <duration> <path>: open-model load through the gateway Service from an in-cluster Job.
k6_start() {
  name=$1; rate=$2; duration=$3; path=$4
  kubectl -n "$NS" create configmap "$name" --from-literal=script.js="import http from 'k6/http';
import { check } from 'k6';
export const options = {
  scenarios: { load: { executor: 'constant-arrival-rate', rate: $rate, timeUnit: '1s', duration: '$duration',
                       preAllocatedVUs: 200, maxVUs: 1000 } },
  thresholds: { http_req_failed: ['rate==0'], checks: ['rate==1'] },
  summaryTrendStats: ['min', 'med', 'p(99)', 'p(99.9)', 'max'],
};
export default function () {
  const r = http.get('http://gateway.$NS.svc.cluster.local$path', { timeout: '10s' });
  check(r, { 'status 200': (x) => x.status === 200 });
}" >/dev/null
  kubectl -n "$NS" apply -f - >/dev/null <<YAML
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: {name: loadgen}
spec:
  podSelector: {matchLabels: {app.kubernetes.io/name: loadgen}}
  policyTypes: [Ingress, Egress]
  egress:
    - to: [{podSelector: {matchLabels: {app.kubernetes.io/name: gateway}}}]
      ports: [{protocol: TCP, port: 8080}]
---
apiVersion: batch/v1
kind: Job
metadata: {name: $name}
spec:
  backoffLimit: 0
  template:
    metadata: {labels: {app.kubernetes.io/name: loadgen}}
    spec:
      restartPolicy: Never
      automountServiceAccountToken: false
      securityContext: {runAsNonRoot: true, runAsUser: 12345, seccompProfile: {type: RuntimeDefault}}
      containers:
        - name: k6
          image: $K6_IMAGE
          args: [run, --quiet, /scripts/script.js]
          resources: {requests: {cpu: "1", memory: 256Mi}, limits: {memory: 1Gi}}
          securityContext: {allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: {drop: [ALL]}}
          volumeMounts: [{name: s, mountPath: /scripts}, {name: tmp, mountPath: /tmp}]
      volumes:
        - {name: s, configMap: {name: $name}}
        - {name: tmp, emptyDir: {}}
YAML
  kubectl -n "$NS" wait --for=condition=Ready pod -l job-name="$name" --timeout=180s >/dev/null
  log "load $name: $rate req/s for $duration on $path"
}
# k6_finish <name>: waits, prints the summary, returns non-zero unless every request succeeded.
k6_finish() {
  name=$1
  kubectl -n "$NS" wait --for=condition=Complete --timeout=900s "job/$name" >/dev/null 2>&1 || true
  kubectl -n "$NS" logs "job/$name" | grep -E 'http_reqs|http_req_failed|http_req_duration|checks|iterations|✓|✗' \
    | tee "$LOCAL/$name.txt"
  ok=$(kubectl -n "$NS" get job "$name" -o jsonpath='{.status.succeeded}')
  kubectl -n "$NS" delete configmap "$name" >/dev/null
  [ "$ok" = 1 ]
}
# gw_code <path>: HTTP status through a port-forward to the gateway Service.
gw_code() {
  curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://localhost:${GW_PORT:-18080}$1" || echo 000
}
gw_forward() {
  kubectl -n "$NS" port-forward svc/gateway "${GW_PORT:-18080}:80" >/dev/null 2>&1 &
  GW_PF=$!
  i=0; until [ "$(gw_code /healthz-none)" != 000 ]; do i=$((i+1)); [ $i -gt 30 ] && die "gateway port-forward"; sleep 1; done
}
# admin_code <role> <path>: HTTP status of a GET on the Admin API.
admin_code() {
  curl -s -o /dev/null -w '%{http_code}' --cacert "$LOCAL/ca.pem" \
    -H "authorization: Bearer $("$HERE/mint-token.sh" "$1" "$NS")" "https://localhost:$CP_PORT/admin/v1/$2"
}
