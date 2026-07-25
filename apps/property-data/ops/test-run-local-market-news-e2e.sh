#!/usr/bin/env bash
set -Eeuo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNER="${OPS_DIR}/run-local-market-news-e2e.sh"

bash -n "${RUNNER}"
grep -Fq 'HOME_NEWS_NAVER_ENABLED=true' "${RUNNER}"
grep -Fq 'HOME_NEWS_NAVER_CLIENT_ID' "${RUNNER}"
grep -Fq 'HOME_NEWS_NAVER_CLIENT_SECRET' "${RUNNER}"
grep -Fq 'NAVER_NEWS_API_KEY_ID' "${RUNNER}"
grep -Fq 'NAVER_NEWS_API_KEY' "${RUNNER}"
grep -Fq 'marketNewsMajorSelectionJob' "${RUNNER}"
grep -Fq 'marketNewsGeneralJob' "${RUNNER}"
grep -Fq 'BOOTSTRAP:' "${RUNNER}"
grep -Fq 'marketNewsMajorComplexJob' "${RUNNER}"
grep -Fq 'marketNewsRetentionJob' "${RUNNER}"
grep -Fq 'marketNewsQualitySampleJob' "${RUNNER}"
grep -Fq 'NEWS_POLICY_VERSION="${MARKET_NEWS_E2E_POLICY_VERSION:-NEWS_V3}"' "${RUNNER}"
grep -Fq '"policyVersion=${NEWS_POLICY_VERSION}"' "${RUNNER}"
grep -Fq 'market_news_quality_review_set' "${RUNNER}"
grep -Fq "build_status = 'PUBLISHED'" "${RUNNER}"
grep -Fq "market-news:current:NATIONWIDE:_" "${RUNNER}"
grep -Fq "market-news:last-good:NATIONWIDE:_" "${RUNNER}"
grep -Fq 'MARKET_NEWS_E2E_REDIS_CONTAINER' "${RUNNER}"
grep -Fq 'MARKET_NEWS_E2E_REDIS_PORT:-16379' "${RUNNER}"
grep -Fq 'docker exec' "${RUNNER}"
grep -Fq 'MARKET_NEWS_E2E_SKIP_BUILD' "${RUNNER}"
grep -Fq -- '-f -' "${RUNNER}"
grep -Fq 'JOIN market_news_snapshot snapshot USING (snapshot_id)' "${RUNNER}"
grep -Fq "article.provided_at < snapshot.generated_at - interval '30 days'" "${RUNNER}"
grep -Fq '/api/v1/insights/news?scope=NATIONWIDE&category=ALL&limit=20' "${RUNNER}"
grep -Fq 'MARKET_NEWS_E2E_PSQL_DSN에 password를 포함하지 마세요' "${RUNNER}"
grep -Fq 'provider title, description, URL은 evidence 파일에 기록하지 않습니다' "${RUNNER}"

if grep -Eq '(docker compose down -v|docker volume (rm|prune)|docker system prune|flyway.*(clean|repair))' "${RUNNER}"; then
  printf 'ERROR: destructive Docker/Flyway command found\n' >&2
  exit 1
fi
if grep -Fq '.env' "${RUNNER}"; then
  printf 'ERROR: runner must receive injected environment and must not read private env files\n' >&2
  exit 1
fi

if alias_output="$(
  DB_JDBC_URL=jdbc:postgresql://localhost/test \
  DB_USERNAME=test \
  DB_PASSWORD=test \
  NAVER_NEWS_API_KEY_ID=api-hub-client-id \
  NAVER_NEWS_API_KEY=api-hub-client-secret \
  MARKET_NEWS_E2E_PSQL_DSN=postgresql://test@localhost/test \
  MARKET_NEWS_E2E_RUN_DATE=invalid \
    "${RUNNER}" 2>&1
)"; then
  printf 'ERROR: invalid run date unexpectedly passed environment validation\n' >&2
  exit 1
fi
grep -Fq '사용법:' <<< "${alias_output}"
if grep -Fq 'NAVER_NEWS_API_KEY_ID 또는 HOME_NEWS_NAVER_CLIENT_ID' <<< "${alias_output}"; then
  printf 'ERROR: NAVER_NEWS_API_KEY_ID alias was not accepted\n' >&2
  exit 1
fi
if grep -Fq 'NAVER_NEWS_API_KEY 또는 HOME_NEWS_NAVER_CLIENT_SECRET' <<< "${alias_output}"; then
  printf 'ERROR: NAVER_NEWS_API_KEY secret alias was not accepted\n' >&2
  exit 1
fi

printf 'market news local E2E runner contract: pass\n'
