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
KAKAO_MAP_APP_KEY=public-test-key \
MARKET_NEWS_ENABLED=true \
  docker buildx bake --print >"${tmp_dir}/bake.json"

jq -e '
  (.group.default.targets | sort) == ([
    "property-api", "property-batch", "property-flyway",
    "admin-api", "admin-migration", "admin-ops",
    "user-api", "user-insight-worker", "user-flyway", "source-data-migration",
    "public-gateway", "admin-gateway", "backup", "ops-bootstrap", "ml", "ai", "chat-bff",
    "budget-postgres", "budget-valkey"
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
  (.target["budget-postgres"].dockerfile == "infra/budget/postgres/Dockerfile") and
  (.target["budget-valkey"].dockerfile == "infra/budget/valkey/Dockerfile") and
  ((.target["public-gateway"].args | has("VITE_USER_API_SERVER_IP")) | not) and
  (.target["public-gateway"].args.VITE_MARKET_NEWS_ENABLED == "true")
' "${tmp_dir}/bake.json" >/dev/null

if ! jq -e '([.target[] | .platforms] | all(. == ["linux/amd64"]))' \
  "${tmp_dir}/bake.json" >/dev/null; then
  echo '상태: Fail - release Bake target이 production ECS X86_64 architecture와 일치하지 않습니다.' >&2
  exit 1
fi

release_workflow=".github/workflows/release-images.yml"
if grep -Fq 'docker/setup-qemu-action@' "${release_workflow}"; then
  echo '상태: Fail - amd64-only release workflow에 QEMU 설정이 남아 있습니다.' >&2
  exit 1
fi

corretto_runtime='amazoncorretto:21-alpine@sha256:58c1d555f4ff3be0cfe90d3b4d1762bde080b57afbb71d48657b9d22748cad5b'
for dockerfile in \
  apps/property-data/api/Dockerfile \
  apps/property-data/batch/Dockerfile \
  apps/admin/service/Dockerfile \
  apps/user/service/Dockerfile \
  apps/source-data/Dockerfile \
  apps/chat-bff/Dockerfile; do
  if ! grep -Fq "FROM ${corretto_runtime}" "${dockerfile}"; then
    echo "상태: Fail - ${dockerfile}의 Java runtime base가 검증된 digest로 고정되지 않았습니다." >&2
    exit 1
  fi
done

if grep -Eq 'STAGING_PUBLIC_ORIGIN|PUBLIC_ORIGIN|VITE_USER_API_SERVER_IP' "${release_workflow}"; then
  echo '상태: Fail - release public gateway에 environment origin build dependency가 남아 있습니다.' >&2
  exit 1
fi

for dockerfile in apps/web/Dockerfile apps/admin/web/Dockerfile; do
  grep -Fq 'cgr.dev/chainguard/nginx:latest-dev@sha256:22ee56150b99f1d1955637a96f1b0b9a9a6c047bbc48fe5e5b9004155f0e9087' \
    "${dockerfile}" || {
    echo "상태: Fail - ${dockerfile}의 gateway runtime base가 검증된 Chainguard digest가 아닙니다." >&2
    exit 1
  }
done
grep -Fq 'cgr.dev/chainguard/python:latest-dev@sha256:3be081f6cae8f1678609f6ae00b1dfebd6819c3ce75b7c574663af84afe99cc4' \
  apps/ai/Dockerfile || {
  echo '상태: Fail - AI runtime base가 검증된 Chainguard digest로 고정되지 않았습니다.' >&2
  exit 1
}
for dockerfile in apps/ml/Dockerfile infra/backup/Dockerfile infra/bootstrap/Dockerfile; do
  grep -Fq 'cgr.dev/chainguard/wolfi-base:latest@sha256:003627df3c1e1bba0c4116afcddb314aca9594ee2328c7e876a8081a6c988b2e' \
    "${dockerfile}" || {
    echo "상태: Fail - ${dockerfile}의 runtime base가 검증된 Wolfi digest가 아닙니다." >&2
    exit 1
  }
done

grep -Fq 'postgres:17-alpine@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193' \
  infra/budget/postgres/Dockerfile || {
  echo '상태: Fail - budget PostgreSQL base가 검증된 Alpine digest로 고정되지 않았습니다.' >&2
  exit 1
}
for source_sha in \
  af5b731c145c1d13c4e3b4eeb7d167e94e845e440f71e3496b4ed8dae0291960 \
  af9ab591854d52a0d1115f90b797ef1cd60d01b85a11ff813073689e332272ff; do
  grep -Fq "${source_sha}" infra/budget/postgres/Dockerfile || {
    echo "상태: Fail - budget PostgreSQL source checksum ${source_sha}이 고정되지 않았습니다." >&2
    exit 1
  }
done
grep -Fq 'unlink /usr/local/bin/gosu' infra/budget/postgres/Dockerfile || {
  echo '상태: Fail - budget PostgreSQL image에서 사용하지 않는 gosu가 제거되지 않았습니다.' >&2
  exit 1
}
grep -Fq -- '-DENABLE_TIFF=OFF' infra/budget/postgres/Dockerfile || {
  echo '상태: Fail - budget PostgreSQL의 PROJ build에서 TIFF가 비활성화되지 않았습니다.' >&2
  exit 1
}
grep -Fq -- '--without-raster' infra/budget/postgres/Dockerfile || {
  echo '상태: Fail - budget PostgreSQL의 PostGIS raster surface가 비활성화되지 않았습니다.' >&2
  exit 1
}
grep -Fq 'make with_llvm=no -j1' infra/budget/postgres/Dockerfile || {
  echo '상태: Fail - budget PostgreSQL의 PostGIS build가 deterministic serial mode가 아닙니다.' >&2
  exit 1
}

for dockerfile in apps/property-data/db/Dockerfile apps/user/service/Dockerfile; do
  grep -Fq 'redgate/flyway:13.0-alpine@sha256:6a67d90135c8ef73299a7486da54b88f285426eea4ea1947372ffbc7b52a327b' \
    "${dockerfile}" || {
    echo "상태: Fail - ${dockerfile}의 Flyway source가 검증된 13.0 digest가 아닙니다." >&2
    exit 1
  }
  grep -Fq '/flyway/lib/rgcompare' "${dockerfile}" || {
    echo "상태: Fail - ${dockerfile}가 사용하지 않는 Flyway comparison runtime을 제거하지 않습니다." >&2
    exit 1
  }
  grep -Fq '/flyway/drivers' "${dockerfile}" || {
    echo "상태: Fail - ${dockerfile}가 PostgreSQL 외 Flyway driver를 제거하지 않습니다." >&2
    exit 1
  }
done

if rg -q 'eclipse-temurin:21-(?:jdk|jre)@|nginxinc/nginx-unprivileged:1[.]27-alpine@|python:3[.](?:10|14)-slim@|postgres:17-bookworm@|redgate/flyway:(?:11[.]7[.]2|12[.]4[.]0)@' \
  apps infra/backup infra/bootstrap; then
  echo '상태: Fail - release Dockerfile에 차단된 취약 base digest가 남아 있습니다.' >&2
  exit 1
fi

prepare_evidence_line="$(grep -nF 'name: Prepare release failure evidence' "${release_workflow}" | cut -d: -f1)"
build_images_line="$(grep -nF 'name: Build and publish SHA and SemVer images' "${release_workflow}" | cut -d: -f1)"
if [[ -z "${prepare_evidence_line}" || -z "${build_images_line}" || "${prepare_evidence_line}" -ge "${build_images_line}" ]]; then
  echo '상태: Fail - release failure evidence가 image build 전에 준비되지 않습니다.' >&2
  exit 1
fi

docker build --tag "${backup_image}" --file infra/backup/Dockerfile .
[[ "$(docker inspect --format '{{.Config.User}}' "${backup_image}")" == '10001:10001' ]]
[[ "$(docker inspect --format '{{json .Config.Entrypoint}}' "${backup_image}")" == '["/usr/local/bin/home-search-db-backup"]' ]]
[[ "$(docker inspect --format '{{json .Config.Cmd}}' "${backup_image}")" == '["--backup-all","/backup"]' ]]
docker run --rm --entrypoint bash "${backup_image}" -c \
  'command -v pg_dump >/dev/null && command -v pg_restore >/dev/null && command -v initdb >/dev/null && command -v aws >/dev/null && command -v zstd >/dev/null && test -f /usr/lib/postgresql17/postgis-3.so && test -d "${HOME_BACKUP_REPO_ROOT}/apps/property-data/db/migration/api" && test -d "${HOME_BACKUP_REPO_ROOT}/apps/ai/ai_service/datasets/migrations" && test -d "${HOME_BACKUP_REPO_ROOT}/apps/source-data/src/main/resources/db/migration/coordinate-source" && test -f "${HOME_BACKUP_REPO_ROOT}/infra/migration/data-only-allowlist.json" && test ! -e /model'
docker run --rm "${backup_image}" --data-validate-catalog /opt/home-search/infra/migration/data-only-allowlist.json

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
