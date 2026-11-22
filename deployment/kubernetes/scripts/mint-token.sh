#!/bin/sh
# Prints a short-lived dev Admin API token (RS256, signed by openssl with the dev OIDC key from dev-secrets.sh).
#   mint-token.sh <viewer|editor|publisher|admin> [namespace] [ttl-seconds]
set -eu
ROLE=$1; NS=${2:-apigw-dev}; TTL=${3:-900}
D="$(cd "$(dirname "$0")/.." && pwd)/.local/$NS"
b64() { base64 | tr '+/' '-_' | tr -d '=\n'; }
now=$(date +%s)
h=$(printf '{"alg":"RS256","typ":"JWT","kid":"dev-1"}' | b64)
p=$(printf '{"iss":"https://issuer.apigw-dev.invalid","aud":"admin-api","sub":"dev-%s","roles":["%s"],"iat":%d,"nbf":%d,"exp":%d}' \
  "$ROLE" "$ROLE" "$now" "$now" "$((now + TTL))" | b64)
s=$(printf '%s.%s' "$h" "$p" | openssl dgst -sha256 -sign "$D/oidc-key.pem" | b64)
printf '%s.%s.%s\n' "$h" "$p" "$s"
