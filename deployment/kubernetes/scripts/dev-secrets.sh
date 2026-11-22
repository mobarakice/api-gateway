#!/bin/sh
# Creates the Secrets the dev overlay references, in namespace $1 (default apigw-dev): dev CA, etcd and
# control-plane certificates, random passwords, the object-store keys and rclone remote, and a dev OIDC signing
# key + JWKS (tokens: mint-token.sh). Material is generated once into deployment/kubernetes/.local/<ns>/
# (git-ignored) and re-applied idempotently. Never use this for staging/prod: see docs/operations/kubernetes.md#secrets.
set -eu
NS=${1:-apigw-dev}
D="$(cd "$(dirname "$0")/.." && pwd)/.local/$NS"
mkdir -p "$D"; chmod 700 "$D"; cd "$D"
umask 077
pw() { openssl rand -hex 24; }

if [ ! -s ca.pem ]; then
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 825 -subj "/CN=apigw $NS dev CA" \
    -keyout ca-key.pem -out ca.pem -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
fi
cert() { # name, SANs
  [ -s "$1.pem" ] && return
  openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -subj "/CN=$1" -keyout "$1-key.pem" -out "$1.csr" 2>/dev/null
  printf '%s\n' "subjectAltName=$2" "extendedKeyUsage=serverAuth,clientAuth" "keyUsage=critical,digitalSignature" > "$1.ext"
  openssl x509 -req -in "$1.csr" -CA ca.pem -CAkey ca-key.pem -CAcreateserial -days 825 -extfile "$1.ext" -out "$1.pem" 2>/dev/null
  rm -f "$1.csr" "$1.ext" ca.srl
}
svc() { echo "DNS:$1,DNS:$1.$NS,DNS:$1.$NS.svc,DNS:$1.$NS.svc.cluster.local"; }
cert etcd "$(svc etcd),DNS:*.etcd,DNS:*.etcd.$NS,DNS:*.etcd.$NS.svc,DNS:*.etcd.$NS.svc.cluster.local,DNS:localhost,IP:127.0.0.1"
cert control-plane "$(svc control-plane),DNS:localhost,IP:127.0.0.1"
for s in etcd-root etcd-gateway etcd-control-plane s3-access s3-secret; do [ -s "$s" ] || pw > "$s"; done
if [ ! -s oidc-key.pem ]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out oidc-key.pem 2>/dev/null
  n=$(openssl rsa -in oidc-key.pem -noout -modulus | cut -d= -f2 | xxd -r -p | base64 | tr '+/' '-_' | tr -d '=\n')
  printf '{"keys":[{"kty":"RSA","kid":"dev-1","use":"sig","alg":"RS256","n":"%s","e":"AQAB"}]}\n' "$n" > jwks.json
fi

k() { kubectl -n "$NS" "$@" --dry-run=client -o yaml | kubectl apply -f - >/dev/null; }
kubectl get namespace "$NS" >/dev/null 2>&1 || kubectl create namespace "$NS" >/dev/null
kubectl label namespace "$NS" --overwrite pod-security.kubernetes.io/enforce=restricted >/dev/null
k create secret generic etcd-tls --from-file=tls.crt=etcd.pem --from-file=tls.key=etcd-key.pem --from-file=ca.crt=ca.pem
k create secret generic etcd-ca --from-file=ca.crt=ca.pem
k create secret generic control-plane-tls --from-file=tls.crt=control-plane.pem --from-file=tls.key=control-plane-key.pem
k create secret generic etcd-root --from-file=password=etcd-root
k create secret generic etcd-gateway --from-file=password=etcd-gateway
k create secret generic etcd-control-plane --from-file=password=etcd-control-plane
k create secret generic control-plane-oidc --from-file=jwks.json=jwks.json
k create secret generic object-store-keys --from-file=access-key=s3-access --from-file=secret-key=s3-secret
k create secret generic etcd-backup-s3 \
  --from-literal=RCLONE_CONFIG_BACKUP_TYPE=s3 --from-literal=RCLONE_CONFIG_BACKUP_PROVIDER=Other \
  --from-literal=RCLONE_CONFIG_BACKUP_ENDPOINT=http://object-store:9000 \
  --from-literal=RCLONE_CONFIG_BACKUP_ACCESS_KEY_ID="$(cat s3-access)" \
  --from-literal=RCLONE_CONFIG_BACKUP_SECRET_ACCESS_KEY="$(cat s3-secret)"
echo "dev secrets applied to $NS (material in $D)"
