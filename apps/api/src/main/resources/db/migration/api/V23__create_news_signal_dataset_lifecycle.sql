CREATE VIEW news_signal_dataset_view AS
SELECT
    feature.id AS feature_id,
    feature.article_observation_id,
    feature.source,
    feature.source_key,
    observation.publisher,
    observation.title,
    observation.url,
    observation.provider_url,
    observation.snippet,
    observation.published_at,
    observation.provider_pub_at,
    feature.first_seen_at,
    feature.feature_date_kst,
    observation.news_date_kst,
    observation.collected_at AS article_collected_at,
    feature.region_tags,
    feature.complex_candidates,
    feature.topic_tags,
    feature.impact_target,
    feature.impact_direction,
    feature.sentiment,
    feature.confidence,
    feature.extraction_version,
    feature.evidence_level,
    feature.created_at AS feature_created_at
FROM news_signal_feature feature
JOIN news_article_observation observation
    ON observation.id = feature.article_observation_id
   AND observation.source = feature.source
   AND observation.source_key = feature.source_key;

CREATE VIEW news_article_observation_cleanup_candidate_view AS
SELECT
    observation.id AS article_observation_id,
    observation.source,
    observation.source_key,
    observation.ingest_status,
    observation.first_seen_at,
    observation.collected_at,
    CASE
        WHEN observation.ingest_status IN ('FETCH_FAILED', 'PARSE_FAILED') THEN 'PURGE_PAYLOAD_AFTER_RETRY_WINDOW'
        ELSE 'PURGE_PAYLOAD'
    END AS retention_action,
    CASE
        WHEN observation.ingest_status = 'FEATURED' THEN 'feature row keeps model signal; provider payload is no longer needed'
        WHEN observation.ingest_status = 'DUPLICATE' THEN 'source identity keeps dedupe trace; provider payload is no longer needed'
        WHEN observation.ingest_status = 'SKIPPED_IRRELEVANT' THEN 'source identity keeps collection trace; provider payload is no longer needed'
        WHEN observation.ingest_status = 'TERMS_BLOCKED' THEN 'source identity and failure reason are enough for audit'
        WHEN observation.ingest_status IN ('FETCH_FAILED', 'PARSE_FAILED') THEN 'keep payload during retry window, then purge'
    END AS retention_reason
FROM news_article_observation observation
WHERE observation.raw_provider_payload <> '{}'::jsonb
  AND observation.ingest_status IN (
      'FEATURED',
      'DUPLICATE',
      'SKIPPED_IRRELEVANT',
      'TERMS_BLOCKED',
      'FETCH_FAILED',
      'PARSE_FAILED'
  );

CREATE OR REPLACE FUNCTION purge_news_article_observation_payloads(retention_cutoff TIMESTAMPTZ)
RETURNS TABLE (
    article_observation_id BIGINT,
    source VARCHAR(64),
    source_key VARCHAR(512),
    ingest_status VARCHAR(64),
    purge_action TEXT
)
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY
    UPDATE news_article_observation AS observation
    SET raw_provider_payload = '{}'::jsonb,
        updated_at = now()
    WHERE observation.raw_provider_payload <> '{}'::jsonb
      AND (
          observation.ingest_status IN ('FEATURED', 'DUPLICATE', 'SKIPPED_IRRELEVANT', 'TERMS_BLOCKED')
          OR (
              observation.ingest_status IN ('FETCH_FAILED', 'PARSE_FAILED')
              AND observation.collected_at <= retention_cutoff
          )
      )
    RETURNING
        observation.id,
        observation.source,
        observation.source_key,
        observation.ingest_status,
        'PURGE_PAYLOAD'::text;
END;
$$;
