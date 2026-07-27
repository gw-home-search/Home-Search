#!/usr/bin/env bash
set -Eeuo pipefail

tmp_dir="$(mktemp -d)"
suffix="${RANDOM}-$$"
backup_image="home-search-backup:test-${suffix}"
bootstrap_image="home-search-bootstrap:test-${suffix}"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

infra/bootstrap/test-home-search-bootstrap.sh

REGISTRY=123456789012.dkr.ecr.ap-northeast-2.amazonaws.com \
IMAGE_PREFIX=home-search \
GIT_SHA=0123456789abcdef \
VERSION=1.2.3 \
SOURCE_URL=https://github.com/acme/home-search \
PUBLIC_ORIGIN=https://staging.example.test \
KAKAO_MAP_APP_KEY=public-test-key \
MARKET_NEWS_ENABLED=true \
  docker buildx bake --print >"${tmp_dir}/bake.json"

jq -e '
  (.group.default.targets | sort) == ([
    "property-api", "property-batch", "property-flyway",
    "admin-api", "admin-migration", "admin-ops",
    "user-api", "user-insight-worker", "user-flyway", "source-data-migration",
    "public-gateway", "admin-gateway", "backup", "ops-bootstrap", "ml", "ai", "chat-bff"
  ] | sort) and
  ([.target[] | .labels["org.opencontainers.image.revision"]] | all(. == "0123456789abcdef")) and
  ([.target[] | .labels["org.opencontainers.image.version"]] | all(. == "1.2.3")) and
  ([.target[] | .labels["org.opencontainers.image.title"]] | all(startswith("home-search-"))) and
  ([.target[] | .tags | length] | all(. == 2)) and
  ([.target | to_entries[] | .value.tags[]] | all(test("/home-search/[^:]+:(0123456789abcdef|1[.]2[.]3)$"))) and
  (.target["property-flyway"].platforms == ["linux/amd64"]) and
  (.target["user-flyway"].platforms == ["linux/amd64"]) and
  (.target.ml.context == "apps/ml") and
  (.target.ai.context == "apps/ai") and
  (.target["chat-bff"].dockerfile == "apps/chat-bff/Dockerfile") and
  (.target["public-gateway"].args.VITE_USER_API_SERVER_IP == "https://staging.example.test") and
  (.target["public-gateway"].args.VITE_MARKET_NEWS_ENABLED == "true")
' "${tmp_dir}/bake.json" >/dev/null

docker build --tag "${backup_image}" --file infra/backup/Dockerfile .
[[ "$(docker inspect --format '{{.Config.User}}' "${backup_image}")" == '10001:10001' ]]
[[ "$(docker inspect --format '{{json .Config.Entrypoint}}' "${backup_image}")" == '["/usr/local/bin/home-search-db-backup"]' ]]
[[ "$(docker inspect --format '{{json .Config.Cmd}}' "${backup_image}")" == '["--backup-all","/backup"]' ]]
docker run --rm --entrypoint bash "${backup_image}" -c \
  'command -v pg_dump >/dev/null && command -v pg_restore >/dev/null && command -v initdb >/dev/null && command -v aws >/dev/null && test -d "${HOME_BACKUP_REPO_ROOT}/apps/property-data/db/migration/api" && test ! -e /model'

docker build --tag "${bootstrap_image}" --file infra/bootstrap/Dockerfile .
[[ "$(docker inspect --format '{{.Config.User}}' "${bootstrap_image}")" == '10001:10001' ]]
[[ "$(docker inspect --format '{{json .Config.Entrypoint}}' "${bootstrap_image}")" == '["/usr/local/bin/home-search-bootstrap"]' ]]
[[ "$(docker inspect --format '{{json .Config.Cmd}}' "${bootstrap_image}")" == '["secret-bootstrap"]' ]]
docker run --rm --entrypoint bash "${bootstrap_image}" -c \
  'command -v psql >/dev/null && command -v createdb >/dev/null && command -v aws >/dev/null && command -v jq >/dev/null && command -v openssl >/dev/null'

for dockerfile in \
  apps/property-data/api/Dockerfile \
  apps/admin/service/Dockerfile \
  apps/user/service/Dockerfile \
  apps/web/Dockerfile \
  apps/admin/web/Dockerfile \
  apps/ml/Dockerfile \
  apps/ai/Dockerfile \
  apps/chat-bff/Dockerfile; do
  grep -q '^HEALTHCHECK ' "${dockerfile}"
done

echo '상태: Pass - Bake target, SHA/SemVer tag, OCI label, architecture, backup runtime 및 health 경계를 확인했습니다.'
