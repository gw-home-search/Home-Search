ALTER TABLE public.raw_trade_ingest
    ADD COLUMN registration_date_raw text,
    ADD COLUMN registration_date date,
    ADD COLUMN cancellation_date_raw text,
    ADD COLUMN cancellation_date date;

CREATE INDEX ix_raw_trade_ingest_execution_registration_date
    ON public.raw_trade_ingest (execution_correlation_id, registration_date, source, source_key)
    WHERE execution_correlation_id IS NOT NULL;

CREATE INDEX ix_raw_trade_ingest_execution_cancellation_date
    ON public.raw_trade_ingest (execution_correlation_id, cancellation_date, source, source_key)
    WHERE execution_correlation_id IS NOT NULL;

ALTER TABLE public.market_insight_snapshot
    DROP CONSTRAINT ck_market_insight_snapshot_period_type,
    ADD CONSTRAINT ck_market_insight_snapshot_period_type
        CHECK (period_type IN ('DAILY', 'WEEKLY', 'ROLLING_7D', 'MONTHLY')),
    DROP CONSTRAINT ck_market_insight_snapshot_build_status,
    ADD CONSTRAINT ck_market_insight_snapshot_build_status
        CHECK (build_status IN ('BUILDING', 'PUBLISHED', 'REJECTED', 'SUPERSEDED')),
    ADD COLUMN missing_registration_date_count integer NOT NULL DEFAULT 0,
    ADD COLUMN invalid_registration_date_count integer NOT NULL DEFAULT 0,
    ADD COLUMN missing_cancellation_date_count integer NOT NULL DEFAULT 0,
    ADD COLUMN invalid_cancellation_date_count integer NOT NULL DEFAULT 0,
    ADD COLUMN superseded_by_snapshot_id uuid,
    ADD CONSTRAINT fk_market_insight_snapshot_superseded_by
        FOREIGN KEY (superseded_by_snapshot_id)
        REFERENCES public.market_insight_snapshot(snapshot_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_market_insight_snapshot_quality_counts
        CHECK (missing_registration_date_count >= 0
            AND invalid_registration_date_count >= 0
            AND missing_cancellation_date_count >= 0
            AND invalid_cancellation_date_count >= 0),
    ADD CONSTRAINT ck_market_insight_snapshot_superseded_link
        CHECK ((build_status = 'SUPERSEDED' AND superseded_by_snapshot_id IS NOT NULL)
            OR (build_status <> 'SUPERSEDED' AND superseded_by_snapshot_id IS NULL));

CREATE INDEX ix_market_insight_snapshot_source_execution
    ON public.market_insight_snapshot (source_execution_id, period_type, build_status)
    WHERE source_execution_id IS NOT NULL;

ALTER TABLE public.market_insight_trade_item
    ADD COLUMN registration_date date,
    ADD COLUMN cancellation_date date,
    DROP CONSTRAINT ck_market_insight_trade_item_metric,
    ADD CONSTRAINT ck_market_insight_trade_item_metric
        CHECK (metric_type IN (
            'DAILY_NEW_TRADE', 'DAILY_HIGHEST_DEAL',
            'WEEKLY_NEW_TRADE', 'WEEKLY_HIGHEST_DEAL',
            'ROLLING_7D_NEW_TRADE', 'ROLLING_7D_HIGHEST_DEAL',
            'AREA_RECORD_HIGH', 'AREA_PREVIOUS_RISE', 'AREA_PREVIOUS_FALL',
            'WEEKLY_DISCLOSURE_ACTIVITY', 'CANCELLATION_CORRECTION',
            'TRADE_ACTIVITY_30D', 'AREA_MOMENTUM_30D',
            'AREA_PRICE_RISE_90D', 'AREA_PRICE_FALL_90D',
            'TRADE_VOLUME_RISE_90D', 'TRADE_VOLUME_FALL_90D',
            'REGION_OBSERVED_CHANGE_MONTHLY'
        ));
