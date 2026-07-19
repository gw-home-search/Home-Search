#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
role_init_script="${script_dir}/init/10-create-service-databases-and-roles.sh"
postgres_container="home-search-postgis"

reject() {
  echo "상태: Fail - $1" >&2
  exit 1
}

: "${AI_DATA_MIGRATOR_DB_PASSWORD:?AI_DATA_MIGRATOR_DB_PASSWORD is required}"
: "${AI_DATA_IMPORTER_DB_PASSWORD:?AI_DATA_IMPORTER_DB_PASSWORD is required}"
: "${AI_DATA_RUNTIME_DB_PASSWORD:?AI_DATA_RUNTIME_DB_PASSWORD is required}"

[[ -f "${role_init_script}" && -s "${role_init_script}" ]] \
  || reject "AI DB role bootstrap 정의를 찾을 수 없습니다."
command -v docker >/dev/null 2>&1 || reject "docker 명령을 찾을 수 없습니다."

state="$(docker inspect \
  --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
  "${postgres_container}" 2>/dev/null)" \
  || reject "기존 PostgreSQL 컨테이너를 확인할 수 없습니다."
[[ "${state}" == "running|healthy" ]] \
  || reject "기존 PostgreSQL 컨테이너가 healthy 상태가 아닙니다."

export AI_DATABASE_ONLY=true
docker exec \
  --env AI_DATA_MIGRATOR_DB_PASSWORD \
  --env AI_DATA_IMPORTER_DB_PASSWORD \
  --env AI_DATA_RUNTIME_DB_PASSWORD \
  --env AI_DATABASE_ONLY \
  -i "${postgres_container}" bash -s <"${role_init_script}" \
  || reject "AI DB role bootstrap에 실패했습니다."

echo "상태: Pass - home_search_ai 역할과 DB 경계를 멱등 적용했습니다."
