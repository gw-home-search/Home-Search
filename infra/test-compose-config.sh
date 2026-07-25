#!/usr/bin/env bash
set -euo pipefail

HOME_SEARCH_DB_PASSWORD=compose-validation \
PROPERTY_MIGRATOR_DB_PASSWORD=compose-validation \
USER_RUNTIME_DB_PASSWORD=compose-validation \
USER_MIGRATOR_DB_PASSWORD=compose-validation \
AI_DATA_RUNTIME_DB_PASSWORD=compose-validation \
AI_DATA_MIGRATOR_DB_PASSWORD=compose-validation \
AI_DATA_IMPORTER_DB_PASSWORD=compose-validation \
AI_PROPERTY_READER_DB_PASSWORD=compose-validation \
HOME_AI_MINIO_ROOT_USER=compose-validation-root \
HOME_AI_MINIO_ROOT_PASSWORD=compose-validation-root-password \
HOME_AI_RAW_S3_BUCKET=compose-validation-raw \
AWS_ACCESS_KEY_ID=compose-validation-importer \
AWS_SECRET_ACCESS_KEY=compose-validation-importer-password \
docker compose -f infra/docker-compose.local.yml config --quiet

printf '상태: Pass - local compose 필수 변수와 interpolation을 확인했습니다.\n'
