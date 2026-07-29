#!/usr/bin/env bash
set -Eeuo pipefail

suffix="${RANDOM}-$$"
postgres_image="home-search-budget-postgres:test-${suffix}"
valkey_image="home-search-budget-valkey:test-${suffix}"

docker build --tag "${postgres_image}" --file infra/budget/postgres/Dockerfile .
docker build --tag "${valkey_image}" --file infra/budget/valkey/Dockerfile .

[[ "$(docker inspect --format '{{.Config.User}}' "${postgres_image}")" == '70:70' ]]
[[ "$(docker inspect --format '{{.Config.User}}' "${valkey_image}")" == '999:1000' ]]
for image in "${postgres_image}" "${valkey_image}"; do
  [[ "$(docker inspect --format '{{json .Config.Healthcheck.Test}}' "${image}")" != 'null' ]]
done

docker run --rm --entrypoint sh "${postgres_image}" -c \
  'postgres --version | grep -Eq "PostgreSQL[)]? 17[.]" && test -f /usr/local/share/postgresql/extension/postgis.control'
docker run --rm --entrypoint sh "${valkey_image}" -c \
  'valkey-server --version | grep -Eq "v=8[.]1[.]" && grep -Fxq "appendonly no" /etc/valkey/valkey.conf && grep -Fxq "save \"\"" /etc/valkey/valkey.conf'

echo '상태: Pass - budget PostgreSQL/PostGIS와 Valkey image의 pinned, non-root, healthcheck 경계를 확인했습니다.'
