#!/usr/bin/env bash
set -euo pipefail

manual=false
if [[ "${1:-}" == "--manual" ]]; then
  manual=true
  shift
fi

gates=(
  backend_changed
  source_data_changed
  frontend_changed
  admin_service_changed
  admin_web_changed
  user_service_changed
  infra_changed
  ml_changed
)

declare -A changed=()
for gate in "${gates[@]}"; do
  changed["$gate"]=false
done

enable_all() {
  local gate
  for gate in "${gates[@]}"; do
    changed["$gate"]=true
  done
}

classify() {
  local path="$1"
  case "$path" in
    .github/workflows/ci.yml|.github/scripts/classify-changes.sh|.github/scripts/test-classify-changes.sh)
      enable_all
      ;;
    apps/property-data/*)
      changed[backend_changed]=true
      ;;
    apps/source-data/*)
      changed[source_data_changed]=true
      ;;
    libs/rtms-ingest-core/*)
      changed[backend_changed]=true
      changed[source_data_changed]=true
      ;;
    apps/web/*)
      changed[frontend_changed]=true
      ;;
    apps/admin/service/*)
      changed[admin_service_changed]=true
      ;;
    apps/admin/web/*)
      changed[admin_web_changed]=true
      ;;
    apps/user/service/*|libs/user-auth-contract/*|docs/USER_SERVICE_PLAN.md)
      changed[user_service_changed]=true
      ;;
    libs/security-jwt-core/*)
      changed[backend_changed]=true
      changed[admin_service_changed]=true
      changed[user_service_changed]=true
      ;;
    apps/ml/*)
      changed[ml_changed]=true
      ;;
    infra/docker-compose.local.yml)
      changed[backend_changed]=true
      changed[source_data_changed]=true
      changed[user_service_changed]=true
      changed[infra_changed]=true
      changed[ml_changed]=true
      ;;
    infra/*)
      changed[backend_changed]=true
      changed[infra_changed]=true
      ;;
    docs/API_CONTRACT.md)
      changed[backend_changed]=true
      changed[frontend_changed]=true
      ;;
    docs/DATA_STORAGE.md)
      changed[backend_changed]=true
      changed[source_data_changed]=true
      ;;
    docs/INFRA_AND_ENV.md)
      changed[backend_changed]=true
      changed[source_data_changed]=true
      changed[user_service_changed]=true
      changed[infra_changed]=true
      ;;
    settings.gradle|build.gradle|gradle.properties|gradle/*)
      changed[backend_changed]=true
      ;;
  esac
}

if [[ "$manual" == "true" ]]; then
  enable_all
fi

if (($# > 0)); then
  for path in "$@"; do
    classify "$path"
  done
else
  while IFS= read -r path; do
    [[ -z "$path" ]] || classify "$path"
  done
fi

for gate in "${gates[@]}"; do
  printf '%s=%s\n' "$gate" "${changed[$gate]}"
done
