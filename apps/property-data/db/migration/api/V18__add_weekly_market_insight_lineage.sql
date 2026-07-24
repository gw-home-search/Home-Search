ALTER TABLE public.market_insight_trade_item
    DROP CONSTRAINT ck_market_insight_trade_item_metric;

ALTER TABLE public.market_insight_trade_item
    ADD CONSTRAINT ck_market_insight_trade_item_metric
        CHECK (metric_type IN (
            'DAILY_NEW_TRADE', 'DAILY_HIGHEST_DEAL',
            'WEEKLY_NEW_TRADE', 'WEEKLY_HIGHEST_DEAL',
            'AREA_RECORD_HIGH', 'AREA_PREVIOUS_RISE', 'AREA_PREVIOUS_FALL',
            'WEEKLY_DISCLOSURE_ACTIVITY', 'CANCELLATION_CORRECTION',
            'TRADE_ACTIVITY_30D', 'AREA_MOMENTUM_30D',
            'AREA_PRICE_RISE_90D', 'AREA_PRICE_FALL_90D',
            'TRADE_VOLUME_RISE_90D', 'TRADE_VOLUME_FALL_90D',
            'REGION_OBSERVED_CHANGE_MONTHLY'
        ));

CREATE TABLE public.market_insight_snapshot_execution (
    snapshot_id uuid NOT NULL,
    execution_id uuid NOT NULL,
    run_date date NOT NULL,
    PRIMARY KEY (snapshot_id, execution_id),
    CONSTRAINT uq_market_insight_snapshot_execution_day UNIQUE (snapshot_id, run_date),
    CONSTRAINT fk_market_insight_snapshot_execution_snapshot
        FOREIGN KEY (snapshot_id)
        REFERENCES public.market_insight_snapshot(snapshot_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_market_insight_snapshot_execution_execution
        FOREIGN KEY (execution_id)
        REFERENCES public.rtms_collection_execution(execution_id)
        ON DELETE RESTRICT
);

CREATE INDEX ix_market_insight_snapshot_execution_execution
    ON public.market_insight_snapshot_execution (execution_id, snapshot_id);

GRANT SELECT, INSERT, UPDATE
    ON TABLE public.market_insight_snapshot_execution
    TO home_search_property_runtime;
