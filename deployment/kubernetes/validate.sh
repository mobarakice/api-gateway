#!/bin/sh
# Validates every overlay: kustomize build | kubeconform -strict, against the minimum supported Kubernetes (1.30)
# and a current release, with CRD schemas for the Prometheus Operator objects. Also fails if the generated
# PrometheusRule drifted from deployment/observability/prometheus/rules/apigw-alerts.yml.
# Needs kubectl (kustomize built in) and Docker (kubeconform runs from its official image).
set -eu
cd "$(dirname "$0")"
KUBECONFORM=ghcr.io/yannh/kubeconform:v0.8.0
VERSIONS="${K8S_VERSIONS:-1.30.0 1.34.0}"
CRDS='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
cp components/monitoring/prometheusrule.yaml "$tmp/rule.before"
components/monitoring/generate-prometheusrule.sh
if ! cmp -s "$tmp/rule.before" components/monitoring/prometheusrule.yaml; then
  echo "prometheusrule.yaml was stale; regenerated it - commit the change" >&2; exit 1
fi

# Schemas are cached between runs (git-ignored); delete the directory to refresh.
cache="$(pwd)/.local/kubeconform-cache"; mkdir -p "$cache"
failed=0
for overlay in overlays/*/; do
  env=$(basename "$overlay")
  kubectl kustomize "$overlay" > "$tmp/$env.yaml"
  for v in $VERSIONS; do
    if docker run --rm -i -v "$cache:/cache" "$KUBECONFORM" -strict -summary -cache /cache -kubernetes-version "$v" \
        -schema-location default -schema-location "$CRDS" < "$tmp/$env.yaml" > "$tmp/out" 2>&1; then
      sed "s/^/[$env k8s $v] /" "$tmp/out"
    else
      sed "s/^/[$env k8s $v] FAIL /" "$tmp/out"; failed=1
    fi
  done
done
[ "$failed" -eq 0 ] || { echo "kubeconform: validation failed" >&2; exit 1; }
echo "kubeconform: all overlays valid"
