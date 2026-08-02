#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077

release_tag="${1:?release tag is required}"
commit_sha="${2:?commit SHA is required}"
rtms_first_id="${3:?first RTMS execution id is required}"
rtms_repeat_id="${4:?repeat RTMS execution id is required}"
news_bootstrap_id="${5:?news bootstrap request id is required}"
review_set_id="${6:?news review set id is required}"

[[ "${release_tag}" =~ ^v[0-9]+[.][0-9]+[.][0-9]+$ ]]
[[ "${commit_sha}" =~ ^[0-9a-f]{40}$ ]]
for id in "${rtms_first_id}" "${rtms_repeat_id}" "${news_bootstrap_id}" "${review_set_id}"; do
  [[ "${id}" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]
done
for name in HOME_BACKUP_PGHOST HOME_BACKUP_PGPORT HOME_BACKUP_PGUSER HOME_BACKUP_PGPASSWORD HOME_RUNTIME_AUDIT_S3_URI; do
  [[ -n "${!name:-}" ]] || { echo "상태: Fail - ${name} 설정이 필요합니다." >&2; exit 1; }
done
[[ "${HOME_RUNTIME_AUDIT_S3_URI}" =~ ^s3://home-search-budget-production-backup-[0-9]{12}/deployment-evidence/runtime-audit$ ]]

tmp_dir="$(mktemp -d)"
cleanup() { find "${tmp_dir}" -depth -delete 2>/dev/null || true; }
trap cleanup EXIT

psql_readonly() {
  PGSSLMODE=require PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=180s' \
    PGPASSWORD="${HOME_BACKUP_PGPASSWORD}" psql -X -Atq -v ON_ERROR_STOP=1 \
      -h "${HOME_BACKUP_PGHOST}" -p "${HOME_BACKUP_PGPORT}" \
      -U "${HOME_BACKUP_PGUSER}" -d home_search "$@"
}

rtms_checks="${tmp_dir}/rtms-checks.json"
psql_readonly -v first_id="${rtms_first_id}" -v repeat_id="${rtms_repeat_id}" >"${rtms_checks}" <<'SQL'
WITH requested_executions AS (
    SELECT execution_id, state, run_date
    FROM public.rtms_collection_execution
    WHERE execution_id IN (:'first_id'::uuid, :'repeat_id'::uuid)
), work_units AS (
    SELECT execution_id,
           count(*) FILTER (WHERE state = 'FAILED') AS failed_count,
           count(*) FILTER (WHERE state IN ('PLANNED', 'RUNNING')) AS unfinished_count
    FROM public.rtms_collection_work_unit
    WHERE execution_id IN (:'first_id'::uuid, :'repeat_id'::uuid)
    GROUP BY execution_id
), repeat_runs AS (
    SELECT coalesce(sum(run.normalized_inserted_count), 0) AS normalized_inserted_count
    FROM public.rtms_collection_work_unit unit
    JOIN public.rtms_ingest_run run ON run.id = unit.rtms_ingest_run_id
    WHERE unit.execution_id = :'repeat_id'::uuid
), current_snapshots AS (
    SELECT period_type, scope_type, region_code, snapshot_id,
           (generated_at AT TIME ZONE 'Asia/Seoul')::date = (now() AT TIME ZONE 'Asia/Seoul')::date AS fresh
    FROM public.market_insight_snapshot
    WHERE build_status = 'PUBLISHED'
      AND period_type IN ('DAILY', 'WEEKLY')
      AND ((scope_type = 'NATIONWIDE' AND region_code IS NULL)
        OR (scope_type = 'SIDO' AND region_code = '11'))
), snapshot_items AS (
    SELECT snapshot_id, count(*) AS item_count
    FROM public.market_insight_trade_item
    GROUP BY snapshot_id
)
SELECT json_build_object(
  'execution_count', (SELECT count(*) FROM requested_executions),
  'executions_terminal', (SELECT count(*) = 2 AND bool_and(state IN ('COMPLETED', 'PARTIAL')) FROM requested_executions),
  'execution_dates_current', (SELECT count(*) = 2 AND bool_and(run_date = (now() AT TIME ZONE 'Asia/Seoul')::date) FROM requested_executions),
  'failed_work_units', coalesce((SELECT sum(failed_count) FROM work_units), 0),
  'unfinished_work_units', coalesce((SELECT sum(unfinished_count) FROM work_units), 0),
  'raw_first_violations', (SELECT count(*) FROM public.trade trade_row JOIN public.raw_trade_ingest raw ON raw.id = trade_row.raw_ingest_id WHERE raw.created_at > trade_row.created_at),
  'repeat_normalized_inserted_count', (SELECT normalized_inserted_count FROM repeat_runs),
  'unexplained_failed_matches', (SELECT count(*) FROM public.raw_trade_ingest WHERE status IN ('MATCH_FAILED', 'PARSE_FAILED') AND nullif(btrim(failure_reason), '') IS NULL),
  'nation_daily_fresh', EXISTS (SELECT 1 FROM current_snapshots WHERE period_type = 'DAILY' AND scope_type = 'NATIONWIDE' AND fresh),
  'seoul_daily_fresh', EXISTS (SELECT 1 FROM current_snapshots WHERE period_type = 'DAILY' AND scope_type = 'SIDO' AND region_code = '11' AND fresh),
  'nation_weekly_fresh', EXISTS (SELECT 1 FROM current_snapshots WHERE period_type = 'WEEKLY' AND scope_type = 'NATIONWIDE' AND fresh),
  'seoul_weekly_fresh', EXISTS (SELECT 1 FROM current_snapshots WHERE period_type = 'WEEKLY' AND scope_type = 'SIDO' AND region_code = '11' AND fresh),
  'seoul_weekly_item_count', coalesce((SELECT max(items.item_count) FROM current_snapshots snapshot JOIN snapshot_items items USING (snapshot_id) WHERE snapshot.period_type = 'WEEKLY' AND snapshot.scope_type = 'SIDO' AND snapshot.region_code = '11' AND snapshot.fresh), 0)
);
SQL

news_checks="${tmp_dir}/news-checks.json"
psql_readonly -v bootstrap_id="BOOTSTRAP:${news_bootstrap_id}" -v review_id="${review_set_id}" >"${news_checks}" <<'SQL'
WITH bootstrap AS (
    SELECT * FROM public.market_news_collection_execution WHERE request_id = :'bootstrap_id'
), current_snapshots AS (
    SELECT snapshot_id, scope_type, region_code, item_count
    FROM public.market_news_snapshot
    WHERE build_status = 'PUBLISHED'
), raw_first_violations AS (
    SELECT count(*) AS count
    FROM current_snapshots snapshot
    JOIN public.market_news_snapshot_item item USING (snapshot_id)
    JOIN public.market_news_article article USING (article_id)
    WHERE NOT EXISTS (
      SELECT 1 FROM public.market_news_raw_item raw
      WHERE raw.article_id = article.article_id AND raw.received_at <= article.created_at
    )
)
SELECT json_build_object(
  'bootstrap_execution_count', (SELECT count(*) FROM bootstrap),
  'bootstrap_terminal', (SELECT count(*) = 1 AND bool_and(state = 'COMPLETED' OR (state = 'PARTIAL' AND bootstrap_truncated)) FROM bootstrap),
  'provider_failures', coalesce((SELECT sum(failed_work_unit_count) FROM bootstrap), 0),
  'bootstrap_raw_item_count', coalesce((SELECT sum(raw_item_count) FROM bootstrap), 0),
  'bootstrap_article_count', coalesce((SELECT sum(article_count) FROM bootstrap), 0),
  'scope_snapshot_count', (SELECT count(*) FROM current_snapshots),
  'nation_item_count', coalesce((SELECT max(item_count) FROM current_snapshots WHERE scope_type = 'NATIONWIDE'), 0),
  'seoul_item_count', coalesce((SELECT max(item_count) FROM current_snapshots WHERE scope_type = 'SIDO' AND region_code = '11'), 0),
  'raw_first_violations', (SELECT count FROM raw_first_violations),
  'duplicate_articles', (SELECT count(*) FROM (SELECT provider, canonical_url_hash FROM public.market_news_article GROUP BY provider, canonical_url_hash HAVING count(*) > 1) duplicate),
  'invalid_titles_or_urls', (SELECT count(*) FROM public.market_news_article WHERE nullif(btrim(title), '') IS NULL OR nullif(btrim(public_url), '') IS NULL),
  'quality_review_count', (SELECT count(*) FROM public.market_news_quality_review_set WHERE review_set_id = :'review_id'::uuid AND policy_version = 'NEWS_V5'),
  'quality_snapshot_count', coalesce((SELECT source_snapshot_count FROM public.market_news_quality_review_set WHERE review_set_id = :'review_id'::uuid AND policy_version = 'NEWS_V5'), 0)
);
SQL

rtms_evidence="${tmp_dir}/rtms-catchup.json"
jq -n --arg tag "${release_tag}" --arg sha "${commit_sha}" --arg created_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg execution_id "manual-catchup:${rtms_first_id}" --slurpfile audit "${rtms_checks}" '
  ($audit[0]) as $a | {
    status:(if $a.execution_count == 2 and $a.executions_terminal and $a.execution_dates_current
      and $a.failed_work_units == 0 and $a.unfinished_work_units == 0
      and $a.raw_first_violations == 0 and $a.repeat_normalized_inserted_count == 0
      and $a.unexplained_failed_matches == 0 and $a.nation_daily_fresh and $a.seoul_daily_fresh
      and $a.nation_weekly_fresh and $a.seoul_weekly_fresh and $a.seoul_weekly_item_count >= 20
      then "pass" else "fail" end),
    release_tag:$tag,commit_sha:$sha,created_at:$created_at,execution_id:$execution_id,
    checks:{task_exit_code:0,all_steps_completed:$a.executions_terminal,
      raw_first:($a.raw_first_violations == 0),duplicate_normalized_trades:$a.repeat_normalized_inserted_count,
      nation_snapshot_fresh:($a.nation_daily_fresh and $a.nation_weekly_fresh),
      seoul_snapshot_fresh:($a.seoul_daily_fresh and $a.seoul_weekly_fresh),
      failed_work_units:$a.failed_work_units,unexplained_failed_matches:$a.unexplained_failed_matches,
      seoul_weekly_item_count:$a.seoul_weekly_item_count},redactions_applied:true
  }' >"${rtms_evidence}"

news_evidence="${tmp_dir}/news-bootstrap.json"
jq -n --arg tag "${release_tag}" --arg sha "${commit_sha}" --arg created_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --slurpfile audit "${news_checks}" '
  ($audit[0]) as $a | {
    status:(if $a.bootstrap_execution_count == 1 and $a.bootstrap_terminal and $a.provider_failures == 0
      and $a.bootstrap_raw_item_count > 0 and $a.bootstrap_article_count > 0 and $a.scope_snapshot_count == 18
      and $a.nation_item_count > 0 and $a.seoul_item_count > 0 and $a.raw_first_violations == 0
      and $a.duplicate_articles == 0 and $a.invalid_titles_or_urls == 0
      and $a.quality_review_count == 1 and $a.quality_snapshot_count == 18
      then "pass" else "fail" end),
    release_tag:$tag,commit_sha:$sha,created_at:$created_at,
    checks:{provider_failures:$a.provider_failures,scope_snapshot_count:$a.scope_snapshot_count,
      nation_non_empty:($a.nation_item_count > 0),seoul_non_empty:($a.seoul_item_count > 0),
      raw_first:($a.raw_first_violations == 0),duplicate_articles:$a.duplicate_articles,
      quality_policy:(if $a.quality_review_count == 1 then "NEWS_V5" else "invalid" end),
      quality_snapshot_count:$a.quality_snapshot_count},redactions_applied:true
  }' >"${news_evidence}"

prefix="${HOME_RUNTIME_AUDIT_S3_URI}/${release_tag}"
aws s3 cp "${rtms_evidence}" "${prefix}/rtms-catchup.json" --sse aws:kms --sse-kms-key-id alias/aws/s3 --only-show-errors
aws s3 cp "${news_evidence}" "${prefix}/news-bootstrap.json" --sse aws:kms --sse-kms-key-id alias/aws/s3 --only-show-errors
echo '상태: Complete - RTMS와 뉴스 read-only 감사 증거를 저장했습니다.'
