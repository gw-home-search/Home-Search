\echo 'RTMS match failure breakdown'
\echo 'required psql variables: lawd_cd, from_ymd, to_ymd'
\set ON_ERROR_STOP on

WITH scoped_raw AS (
    SELECT id, status, lawd_cd, deal_ymd, failure_reason
    FROM raw_trade_ingest
    WHERE source = 'RTMS'
      AND lawd_cd = :'lawd_cd'
      AND deal_ymd BETWEEN :'from_ymd' AND :'to_ymd'
)
SELECT status, count(*) AS raw_count
FROM scoped_raw
GROUP BY status
ORDER BY status;

WITH scoped_raw AS (
    SELECT id, status, lawd_cd, deal_ymd, failure_reason
    FROM raw_trade_ingest
    WHERE source = 'RTMS'
      AND lawd_cd = :'lawd_cd'
      AND deal_ymd BETWEEN :'from_ymd' AND :'to_ymd'
)
SELECT
    e.match_status,
    count(*) AS evidence_count,
    count(*) FILTER (WHERE r.status = 'MATCH_FAILED') AS match_failed_raw_count
FROM scoped_raw r
JOIN trade_match_evidence e ON e.raw_ingest_id = r.id
GROUP BY e.match_status
ORDER BY e.match_status;

WITH scoped_raw AS (
    SELECT id, status, deal_ymd
    FROM raw_trade_ingest
    WHERE source = 'RTMS'
      AND lawd_cd = :'lawd_cd'
      AND deal_ymd BETWEEN :'from_ymd' AND :'to_ymd'
),
evidence_scope AS (
    SELECT r.deal_ymd, r.status AS raw_status, e.*
    FROM scoped_raw r
    JOIN trade_match_evidence e ON e.raw_ingest_id = r.id
)
SELECT
    match_status,
    CASE
        WHEN match_status = 'PNU_UNAVAILABLE' THEN COALESCE(pnu_unavailable_reason, 'UNKNOWN_PNU_REASON')
        WHEN match_status = 'UNMATCHED' AND failure_reason LIKE 'no complex matched aptSeq=%, pnu=%'
            THEN 'NO_COMPLEX_FOR_APTSEQ_AND_PNU'
        WHEN match_status = 'UNMATCHED' AND failure_reason LIKE 'no complex matched pnu=%'
            THEN 'NO_COMPLEX_FOR_PNU'
        WHEN match_status = 'UNMATCHED' THEN 'UNMATCHED_OTHER'
        ELSE COALESCE(failure_reason, 'NO_FAILURE_REASON')
    END AS failure_bucket,
    count(*) AS evidence_count
FROM evidence_scope
WHERE match_status IN ('UNMATCHED', 'PNU_UNAVAILABLE')
GROUP BY match_status, failure_bucket
ORDER BY match_status, evidence_count DESC, failure_bucket;

WITH scoped_raw AS (
    SELECT id, deal_ymd
    FROM raw_trade_ingest
    WHERE source = 'RTMS'
      AND lawd_cd = :'lawd_cd'
      AND deal_ymd BETWEEN :'from_ymd' AND :'to_ymd'
),
evidence_scope AS (
    SELECT r.deal_ymd, e.*
    FROM scoped_raw r
    JOIN trade_match_evidence e ON e.raw_ingest_id = r.id
)
SELECT
    match_status,
    deal_ymd,
    count(*) AS evidence_count
FROM evidence_scope
WHERE match_status IN ('UNMATCHED', 'PNU_UNAVAILABLE')
GROUP BY match_status, deal_ymd
ORDER BY match_status, deal_ymd;

WITH scoped_raw AS (
    SELECT id
    FROM raw_trade_ingest
    WHERE source = 'RTMS'
      AND lawd_cd = :'lawd_cd'
      AND deal_ymd BETWEEN :'from_ymd' AND :'to_ymd'
),
unmatched AS (
    SELECT e.*
    FROM scoped_raw r
    JOIN trade_match_evidence e ON e.raw_ingest_id = r.id
    WHERE e.match_status = 'UNMATCHED'
)
SELECT
    COALESCE(e.derived_pnu, 'NO_DERIVED_PNU') AS derived_pnu,
    count(*) AS evidence_count,
    count(DISTINCT e.apt_seq) AS apt_seq_count,
    count(DISTINCT e.apt_name) AS apt_name_count,
    bool_or(p.id IS NOT NULL) AS parcel_exists,
    bool_or(c.id IS NOT NULL) AS complex_exists
FROM unmatched e
LEFT JOIN parcel p ON p.pnu = e.derived_pnu
LEFT JOIN complex c ON c.parcel_id = p.id
GROUP BY COALESCE(e.derived_pnu, 'NO_DERIVED_PNU')
ORDER BY evidence_count DESC, derived_pnu
LIMIT 50;

WITH scoped_raw AS (
    SELECT id
    FROM raw_trade_ingest
    WHERE source = 'RTMS'
      AND lawd_cd = :'lawd_cd'
      AND deal_ymd BETWEEN :'from_ymd' AND :'to_ymd'
),
unmatched AS (
    SELECT e.*
    FROM scoped_raw r
    JOIN trade_match_evidence e ON e.raw_ingest_id = r.id
    WHERE e.match_status = 'UNMATCHED'
)
SELECT
    CASE
        WHEN p.id IS NULL AND apt_seq_complex.id IS NULL THEN 'PARCEL_AND_APTSEQ_MASTER_MISSING'
        WHEN p.id IS NULL THEN 'PARCEL_MASTER_MISSING'
        WHEN p.id IS NOT NULL AND pnu_complex.id IS NULL AND apt_seq_complex.id IS NULL
            THEN 'COMPLEX_AND_APTSEQ_MASTER_MISSING'
        WHEN p.id IS NOT NULL AND pnu_complex.id IS NULL THEN 'COMPLEX_MASTER_MISSING'
        WHEN apt_seq_complex.id IS NULL THEN 'APTSEQ_MASTER_MISSING'
        ELSE 'MASTER_PRESENT_RECHECK_MATCHER'
    END AS master_gap_bucket,
    count(*) AS evidence_count,
    count(DISTINCT e.derived_pnu) AS derived_pnu_count,
    count(DISTINCT e.apt_seq) AS apt_seq_count,
    count(DISTINCT e.apt_name) AS apt_name_count
FROM unmatched e
LEFT JOIN parcel p ON p.pnu = e.derived_pnu
LEFT JOIN complex pnu_complex ON pnu_complex.parcel_id = p.id
LEFT JOIN complex apt_seq_complex ON apt_seq_complex.apt_seq = e.apt_seq
GROUP BY master_gap_bucket
ORDER BY evidence_count DESC, master_gap_bucket;

WITH scoped_raw AS (
    SELECT id
    FROM raw_trade_ingest
    WHERE source = 'RTMS'
      AND lawd_cd = :'lawd_cd'
      AND deal_ymd BETWEEN :'from_ymd' AND :'to_ymd'
),
pnu_unavailable AS (
    SELECT e.*
    FROM scoped_raw r
    JOIN trade_match_evidence e ON e.raw_ingest_id = r.id
    WHERE e.match_status = 'PNU_UNAVAILABLE'
)
SELECT
    COALESCE(pnu_unavailable_reason, 'UNKNOWN_PNU_REASON') AS pnu_unavailable_reason,
    COALESCE(sgg_cd, 'NULL') AS sgg_cd,
    COALESCE(umd_cd, 'NULL') AS umd_cd,
    count(*) AS evidence_count,
    min(raw_jibun) AS sample_raw_jibun
FROM pnu_unavailable
GROUP BY pnu_unavailable_reason, sgg_cd, umd_cd
ORDER BY evidence_count DESC, pnu_unavailable_reason, sgg_cd, umd_cd
LIMIT 50;

WITH scoped_raw AS (
    SELECT id, source, source_key, status
    FROM raw_trade_ingest
    WHERE source = 'RTMS'
      AND lawd_cd = :'lawd_cd'
      AND deal_ymd BETWEEN :'from_ymd' AND :'to_ymd'
)
SELECT
    count(*) AS received_linked_to_active_trade_count
FROM scoped_raw r
JOIN trade_source_key_registry registry
  ON registry.source = r.source
 AND registry.source_key = r.source_key
JOIN trade t
  ON t.id = registry.trade_id
 AND t.deleted_at IS NULL
WHERE r.status = 'RECEIVED';
