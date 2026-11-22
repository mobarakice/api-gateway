#!/bin/sh
# Rollout drain test: constant-arrival-rate load (k6, open model, in-cluster) through the gateway Service while the
# gateway Deployment is rolled ROLLOUTS times. Passes only if every request succeeded (no errors, all 200).
#   NS=apigw-dev RATE=300 DURATION=180s ROLLOUTS=3 drain-test.sh
set -eu
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
RATE=${RATE:-300}; DURATION=${DURATION:-180s}; ROLLOUTS=${ROLLOUTS:-3}
RUN=drain-$(date +%s)
k6_start "$RUN" "$RATE" "$DURATION" /echo/drain
sleep 15
i=1
while [ $i -le "$ROLLOUTS" ]; do
  log "rollout $i/$ROLLOUTS"
  kubectl -n "$NS" rollout restart deployment/gateway >/dev/null
  kubectl -n "$NS" rollout status deployment/gateway --timeout=300s >/dev/null
  sleep 10
  i=$((i+1))
done
k6_finish "$RUN" || die "drain test: requests failed during rollouts (see $LOCAL/$RUN.txt)"
log "PASS: zero failed requests across $ROLLOUTS gateway rollouts at $RATE req/s"
