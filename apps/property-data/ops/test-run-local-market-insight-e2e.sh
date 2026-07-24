#!/usr/bin/env bash
set -Eeuo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNER="${OPS_DIR}/run-local-market-insight-e2e.sh"

bash -n "${RUNNER}"
grep -Fq 'HOME_INGEST_RTMS_DAILY_LAWD_CDS=""' "${RUNNER}"
grep -Fq 'HOME_INGEST_RTMS_DAILY_LOOKBACK_MONTHS=2' "${RUNNER}"
grep -Fq '"${published}" != "18"' "${RUNNER}"
grep -Fq 'property-flyway.sh" validate' "${RUNNER}"
grep -Fq 'rtmsDailyRefreshJob' "${RUNNER}"
grep -Fq "period_type = 'ROLLING_7D'" "${RUNNER}"
grep -Fq '/api/v1/insights/trades/weekly?scope=NATIONWIDE&limit=10' "${RUNNER}"
grep -Fq 'HOME_INSIGHT_TRADE_ENABLED=true' "${RUNNER}"
grep -Fq 'MARKET_INSIGHT_E2E_MAX_DAILY_ATTEMPTS' "${RUNNER}"
grep -Fq 'MAX_DAILY_ATTEMPTS="${MARKET_INSIGHT_E2E_MAX_DAILY_ATTEMPTS:-5}"' "${RUNNER}"
grep -Fq 'restartAttempt=' "${RUNNER}"
grep -Fq 'MARKET_INSIGHT_E2E_PSQL_DSN에 password를 포함하지 마세요' "${RUNNER}"

if grep -Eq '(docker compose down -v|docker volume (rm|prune)|docker system prune|flyway.*(clean|repair))' "${RUNNER}"; then
  printf 'ERROR: destructive Docker/Flyway command found\n' >&2
  exit 1
fi
if grep -Fq '.env' "${RUNNER}"; then
  printf 'ERROR: runner must receive injected environment and must not read private env files\n' >&2
  exit 1
fi

printf 'market insight local E2E runner contract: pass\n'
