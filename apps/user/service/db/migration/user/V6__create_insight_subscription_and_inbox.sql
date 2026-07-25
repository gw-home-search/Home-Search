CREATE TABLE users.insight_subscription (
    user_id BIGINT PRIMARY KEY
        REFERENCES users.user_account(id) ON DELETE CASCADE,
    in_app_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    email_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    daily_news_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    weekly_trade_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    region_codes VARCHAR(2)[] NOT NULL DEFAULT ARRAY[]::VARCHAR(2)[],
    email_consent_hash CHAR(64),
    email_consented_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT insight_subscription_region_count
        CHECK (cardinality(region_codes) <= 5),
    CONSTRAINT insight_subscription_email_consent
        CHECK (
            NOT email_enabled
            OR (email_consent_hash IS NOT NULL AND email_consented_at IS NOT NULL)
        )
);

CREATE TABLE users.insight_inbox (
    inbox_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL
        REFERENCES users.user_account(id) ON DELETE CASCADE,
    digest_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    property_snapshot_id VARCHAR(200) NOT NULL,
    deep_link VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT insight_inbox_user_digest_unique UNIQUE (user_id, digest_id),
    CONSTRAINT insight_inbox_deep_link
        CHECK (deep_link ~ '^/insights([?][A-Za-z0-9%&=_-]+)?$'),
    CONSTRAINT insight_inbox_expiry CHECK (expires_at > created_at)
);

CREATE INDEX insight_inbox_user_created_idx
    ON users.insight_inbox(user_id, created_at DESC, inbox_id DESC);

CREATE TABLE users.event_consumer_inbox (
    event_id UUID PRIMARY KEY,
    topic_name VARCHAR(200) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(200) NOT NULL,
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version >= 0),
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT event_consumer_inbox_expiry CHECK (expires_at > processed_at)
);

CREATE INDEX event_consumer_inbox_expiry_idx
    ON users.event_consumer_inbox(expires_at);

CREATE TABLE users.event_projection_version (
    event_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(200) NOT NULL,
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version >= 1),
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_type, aggregate_id)
);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'home_search_user_runtime') THEN
        REVOKE ALL ON TABLE users.insight_subscription FROM home_search_user_runtime;
        REVOKE ALL ON TABLE users.insight_inbox FROM home_search_user_runtime;
        REVOKE ALL ON TABLE users.event_consumer_inbox FROM home_search_user_runtime;
        REVOKE ALL ON TABLE users.event_projection_version FROM home_search_user_runtime;
        GRANT SELECT, INSERT, UPDATE
            ON TABLE users.insight_subscription
            TO home_search_user_runtime;
        GRANT SELECT, INSERT, DELETE
            ON TABLE users.insight_inbox
            TO home_search_user_runtime;
        GRANT SELECT, INSERT, DELETE
            ON TABLE users.event_consumer_inbox
            TO home_search_user_runtime;
        GRANT SELECT, INSERT, UPDATE
            ON TABLE users.event_projection_version
            TO home_search_user_runtime;
    END IF;
END $$;
