#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$repo_root/.github/workflows/deploy-staging.yml"

python3 - "$workflow" <<'PY'
from pathlib import Path
import sys

workflow = Path(sys.argv[1]).read_text(encoding="utf-8")
required_fragments = (
    "      enable_market_news_public:\n",
    "      enable_market_news_schedules:\n",
    "      enable_user_insights_public:\n",
    "      enable_property_event_relay_schedule:\n",
    "      enable_property_event_retention_schedule:\n",
    "          ENABLE_MARKET_NEWS_PUBLIC: ${{ inputs.enable_market_news_public }}\n",
    "          ENABLE_MARKET_NEWS_SCHEDULES: ${{ inputs.enable_market_news_schedules }}\n",
    "          ENABLE_USER_INSIGHTS_PUBLIC: ${{ inputs.enable_user_insights_public }}\n",
    "          ENABLE_PROPERTY_EVENT_RELAY_SCHEDULE: ${{ inputs.enable_property_event_relay_schedule }}\n",
    "          ENABLE_PROPERTY_EVENT_RETENTION_SCHEDULE: ${{ inputs.enable_property_event_retention_schedule }}\n",
    "          manifest_news_enabled=\"$(jq -r '.build_flags.market_news_enabled' deployment-evidence/release-manifest.json)\"\n",
    '          [[ "${manifest_news_enabled}" == "${ENABLE_MARKET_NEWS_PUBLIC}" ]]\n',
    "              enable_market_news_public:$enable_market_news_public,\n",
    "              enable_market_news_schedules:$enable_market_news_schedules,\n",
    "              enable_user_insights_public:$enable_user_insights_public,\n",
    "              enable_property_event_relay_schedule:$enable_property_event_relay_schedule,\n",
    "              enable_property_event_retention_schedule:$enable_property_event_retention_schedule,\n",
)
missing = [fragment for fragment in required_fragments if fragment not in workflow]
if missing:
    raise SystemExit("상태: Fail - staging news/insight rollout 계약이 불완전합니다.")
PY

echo "상태: Pass - staging news image flag와 news/insight rollout 입력이 일치합니다."
