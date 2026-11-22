#!/bin/sh
# Local Compose stack bootstrap (dev only; never used in Kubernetes).
#   init.sh certs      dev CA + one server certificate, random passwords, Keycloak realm with test users
#   init.sh etcd-auth  etcd root/gateway/control-plane users and least-privilege roles, then auth enable
# Everything lands in /work (deployment/docker/.local, git-ignored). Nothing is hard-coded.
set -eu
umask 077
W=/work
UID_GID=10001:10001
PREFIX=${GW_PREFIX:-/gw/dev/}

pw() { openssl rand -hex 18; }

certs() {
  mkdir -p "$W/certs" "$W/secrets" "$W/keycloak" "$W/e2e"
  if [ ! -s "$W/certs/ca.pem" ]; then
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 825 -subj "/CN=apigw dev CA" \
      -keyout "$W/certs/ca-key.pem" -out "$W/certs/ca.pem" \
      -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
    openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -subj "/CN=apigw dev" \
      -keyout "$W/certs/server-key.pem" -out "$W/certs/server.csr" 2>/dev/null
    printf '%s\n' "subjectAltName=DNS:localhost,DNS:etcd,DNS:control-plane,DNS:keycloak,DNS:gateway-1,DNS:gateway-2,IP:127.0.0.1" \
      "extendedKeyUsage=serverAuth,clientAuth" "keyUsage=critical,digitalSignature" > "$W/certs/ext.cnf"
    openssl x509 -req -in "$W/certs/server.csr" -CA "$W/certs/ca.pem" -CAkey "$W/certs/ca-key.pem" \
      -CAcreateserial -days 825 -extfile "$W/certs/ext.cnf" -out "$W/certs/server.pem" 2>/dev/null
    rm -f "$W/certs/server.csr" "$W/certs/ext.cnf" "$W/certs/ca.srl"
    echo "init: generated dev CA and server certificate"
  fi
  for s in etcd-root etcd-gateway etcd-control-plane redis-gateway grafana-admin; do
    [ -s "$W/secrets/$s" ] || pw > "$W/secrets/$s"
  done
  if [ ! -s "$W/e2e/users.json" ]; then
    v=$(pw); e=$(pw); p=$(pw); a=$(pw)
    printf '{"viewer":"%s","editor":"%s","publisher":"%s","admin":"%s"}\n' "$v" "$e" "$p" "$a" > "$W/e2e/users.json"
    sed -e "s/__VIEWER__/$v/" -e "s/__EDITOR__/$e/" -e "s/__PUBLISHER__/$p/" -e "s/__ADMIN__/$a/" \
      /realm.template.json > "$W/keycloak/gateway-realm.json"
    echo "init: generated Keycloak test users (passwords in .local/e2e/users.json)"
  fi
  # Services run as 10001; the CA key stays readable by root only.
  chown -R "$UID_GID" "$W/secrets" "$W/keycloak" "$W/certs/server-key.pem"
  chmod 0644 "$W/certs/ca.pem" "$W/certs/server.pem"
  chmod 0600 "$W/certs/server-key.pem" "$W/certs/ca-key.pem"
  chmod 0700 "$W/secrets" && chmod 0600 "$W"/secrets/*
  chmod 0755 "$W/certs" "$W/keycloak" && chmod 0644 "$W/keycloak/gateway-realm.json"
  # e2e passwords are read by Playwright on the host.
  chmod 0755 "$W/e2e" && chmod 0644 "$W/e2e/users.json"
  for d in /data/*; do [ -d "$d" ] && chown "$UID_GID" "$d" && chmod 0700 "$d"; done
  echo "init: certs and secrets ready"
}

ctl() { etcdctl --endpoints=https://etcd:2379 --cacert="$W/certs/ca.pem" --command-timeout=5s "$@"; }

etcd_auth() {
  root=$(cat "$W/secrets/etcd-root")
  i=0
  # Once auth is on (a re-run), the health check itself needs credentials.
  until ctl endpoint health >/dev/null 2>&1 || ctl --user "root:$root" endpoint health >/dev/null 2>&1; do
    i=$((i + 1)); [ "$i" -gt 60 ] && { echo "init: etcd not reachable" >&2; exit 1; }
    sleep 1
  done
  if ctl --user "root:$root" auth status 2>/dev/null | grep -q 'Authentication Status: true'; then
    echo "init: etcd auth already enabled"
    return
  fi
  ctl user add root --new-user-password="$root" >/dev/null
  ctl user grant-role root root >/dev/null
  ctl role add gateway-read >/dev/null
  ctl role grant-permission gateway-read --prefix=true read "$PREFIX" >/dev/null
  ctl role grant-permission gateway-read --prefix=true readwrite "${PREFIX}nodes/" >/dev/null
  ctl user add gateway --new-user-password="$(cat "$W/secrets/etcd-gateway")" >/dev/null
  ctl user grant-role gateway gateway-read >/dev/null
  ctl role add control-plane >/dev/null
  ctl role grant-permission control-plane --prefix=true read "$PREFIX" >/dev/null
  for sub in meta/ routes/ services/ upstreams/ global-plugins/ consumers/ credentials/ plugin-configs/ policies/ \
      certificates/ changesets/ history/ audit/; do
    ctl role grant-permission control-plane --prefix=true readwrite "$PREFIX$sub" >/dev/null
  done
  ctl user add control-plane --new-user-password="$(cat "$W/secrets/etcd-control-plane")" >/dev/null
  ctl user grant-role control-plane control-plane >/dev/null
  ctl auth enable >/dev/null
  echo "init: etcd auth enabled (users root, gateway, control-plane; prefix $PREFIX)"
}

case "${1:-}" in
  certs) certs ;;
  etcd-auth) etcd_auth ;;
  *) echo "usage: init.sh certs|etcd-auth" >&2; exit 2 ;;
esac
