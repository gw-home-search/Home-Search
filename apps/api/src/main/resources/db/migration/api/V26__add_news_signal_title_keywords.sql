ALTER TABLE news_signal_feature
    ADD COLUMN title_keywords JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(title_keywords) = 'array');

CREATE INDEX ix_news_signal_feature_title_keywords
    ON news_signal_feature USING GIN (title_keywords);

DROP VIEW news_signal_dataset_view;

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
    feature.title_keywords,
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
