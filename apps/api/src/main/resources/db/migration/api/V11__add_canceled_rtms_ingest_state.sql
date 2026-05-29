ALTER TABLE raw_trade_ingest
    DROP CONSTRAINT IF EXISTS raw_trade_ingest_status_check;

ALTER TABLE raw_trade_ingest
    DROP CONSTRAINT IF EXISTS ck_raw_trade_ingest_status;

ALTER TABLE raw_trade_ingest
    ADD CONSTRAINT ck_raw_trade_ingest_status
    CHECK (
        status IN (
            'RECEIVED',
            'NORMALIZED',
            'DUPLICATE',
            'CANCELED',
            'MATCH_FAILED',
            'PARSE_FAILED',
            'SKIPPED_INVALID'
        )
    );

ALTER TABLE rtms_ingest_run
    ADD COLUMN canceled_skipped_count BIGINT NOT NULL DEFAULT 0
    CHECK (canceled_skipped_count >= 0);
