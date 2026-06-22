ALTER TABLE news.source_policy
    DROP CONSTRAINT source_policy_source_allowed;

ALTER TABLE news.source_policy
    ADD CONSTRAINT source_policy_source_allowed CHECK (source IN ('NAVER_NEWS_SEARCH', 'AI_ASSISTED_WEB_RESEARCH', 'BIGKINDS_CSV'));

INSERT INTO news.source_policy (
    source,
    policy_version,
    metadata_collection_allowed,
    publisher_page_fetch_allowed,
    full_text_storage_allowed,
    snippet_storage_allowed,
    raw_payload_retention_days,
    notes
)
VALUES (
    'BIGKINDS_CSV',
    'bigkinds-csv-historical-seed-v1',
    true,
    false,
    false,
    false,
    3650,
    'BigKinds licensed historical CSV export is used only for metadata review notes. CSV body text is not stored in notes or raw provider payload.'
)
ON CONFLICT (source) DO NOTHING;

ALTER TABLE news.article_observation
    DROP CONSTRAINT article_observation_source_allowed,
    DROP CONSTRAINT article_observation_discovery_method_allowed,
    DROP CONSTRAINT article_observation_availability_basis_allowed,
    DROP CONSTRAINT article_observation_source_dataset_consistent;

ALTER TABLE news.article_observation
    ADD CONSTRAINT article_observation_source_allowed CHECK (source IN ('NAVER_NEWS_SEARCH', 'AI_ASSISTED_WEB_RESEARCH', 'BIGKINDS_CSV')),
    ADD CONSTRAINT article_observation_discovery_method_allowed CHECK (discovery_method IN ('PROVIDER_API', 'OPENAI_WEB_SEARCH', 'PROVIDER_EXPORT')),
    ADD CONSTRAINT article_observation_availability_basis_allowed CHECK (availability_basis IN ('AI_ASSISTED_RESEARCH_SEED', 'AI_ASSISTED_TRANSITION_SEED', 'REALTIME_OBSERVED', 'LICENSED_HISTORICAL_EXPORT')),
    ADD CONSTRAINT article_observation_source_dataset_consistent CHECK (
        (
            source = 'NAVER_NEWS_SEARCH'
            AND discovery_method = 'PROVIDER_API'
            AND availability_basis = 'REALTIME_OBSERVED'
            AND verification_status = 'SYSTEM_ACCEPTED'
            AND model_dataset_tier = 'OBSERVED_SIGNAL'
            AND review_note_path IS NULL
            AND ai_research_seed_run_id IS NULL
        )
        OR
        (
            source = 'AI_ASSISTED_WEB_RESEARCH'
            AND discovery_method = 'OPENAI_WEB_SEARCH'
            AND availability_basis IN ('AI_ASSISTED_RESEARCH_SEED', 'AI_ASSISTED_TRANSITION_SEED')
            AND verification_status = 'MANUAL_APPROVED'
            AND model_dataset_tier = 'EXPERIMENTAL_SEED'
            AND review_note_path IS NOT NULL
            AND ai_research_seed_run_id IS NOT NULL
        )
        OR
        (
            source = 'BIGKINDS_CSV'
            AND discovery_method = 'PROVIDER_EXPORT'
            AND availability_basis = 'LICENSED_HISTORICAL_EXPORT'
            AND verification_status = 'MANUAL_APPROVED'
            AND model_dataset_tier = 'EXPERIMENTAL_SEED'
            AND review_note_path IS NOT NULL
            AND ai_research_seed_run_id IS NOT NULL
        )
    );

ALTER TABLE news.signal_feature
    DROP CONSTRAINT signal_feature_source_allowed;

ALTER TABLE news.signal_feature
    ADD CONSTRAINT signal_feature_source_allowed CHECK (source IN ('NAVER_NEWS_SEARCH', 'AI_ASSISTED_WEB_RESEARCH', 'BIGKINDS_CSV'));
