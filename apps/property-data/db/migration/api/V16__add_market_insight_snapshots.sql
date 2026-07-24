CREATE TABLE public.market_insight_snapshot (
    snapshot_id uuid PRIMARY KEY,
    period_type varchar(16) NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    scope_type varchar(16) NOT NULL,
    region_code varchar(16),
    build_status varchar(16) NOT NULL,
    data_cutoff timestamptz NOT NULL,
    source_execution_id uuid,
    planned_work_unit_count integer NOT NULL,
    completed_work_unit_count integer NOT NULL,
    partial_work_unit_count integer NOT NULL,
    failed_work_unit_count integer NOT NULL,
    generated_at timestamptz NOT NULL DEFAULT now(),
    rejection_reason varchar(64),
    CONSTRAINT fk_market_insight_snapshot_execution
        FOREIGN KEY (source_execution_id)
        REFERENCES public.rtms_collection_execution(execution_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_market_insight_snapshot_period_type
        CHECK (period_type IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT ck_market_insight_snapshot_period
        CHECK (period_start <= period_end),
    CONSTRAINT ck_market_insight_snapshot_scope
        CHECK ((scope_type = 'NATIONWIDE' AND region_code IS NULL)
            OR (scope_type = 'SIDO' AND region_code IS NOT NULL)),
    CONSTRAINT ck_market_insight_snapshot_build_status
        CHECK (build_status IN ('BUILDING', 'PUBLISHED', 'REJECTED')),
    CONSTRAINT ck_market_insight_snapshot_counts
        CHECK (planned_work_unit_count >= 0
            AND completed_work_unit_count >= 0
            AND partial_work_unit_count >= 0
            AND failed_work_unit_count >= 0
            AND completed_work_unit_count + partial_work_unit_count + failed_work_unit_count
                <= planned_work_unit_count)
);

CREATE UNIQUE INDEX uq_market_insight_snapshot_published_period_scope
    ON public.market_insight_snapshot (
        period_type, period_start, period_end, scope_type, COALESCE(region_code, '')
    )
    WHERE build_status = 'PUBLISHED';

CREATE INDEX ix_market_insight_snapshot_public_lookup
    ON public.market_insight_snapshot (
        period_type, scope_type, region_code, period_end DESC, generated_at DESC
    )
    WHERE build_status = 'PUBLISHED';

CREATE TABLE public.market_insight_trade_item (
    snapshot_id uuid NOT NULL,
    metric_type varchar(48) NOT NULL,
    rank integer NOT NULL,
    trade_id bigint,
    trade_deal_date date,
    comparison_trade_id bigint,
    comparison_trade_deal_date date,
    complex_id bigint NOT NULL,
    deal_amount bigint,
    excl_area numeric(10,2),
    deal_date date,
    disclosed_at timestamptz,
    previous_amount bigint,
    previous_deal_date date,
    delta_amount bigint,
    delta_rate numeric(12,6),
    current_count integer,
    previous_count integer,
    comparison_sample_count integer,
    captured_trade_status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    canceled_at timestamptz,
    PRIMARY KEY (snapshot_id, metric_type, rank),
    CONSTRAINT fk_market_insight_trade_item_snapshot
        FOREIGN KEY (snapshot_id)
        REFERENCES public.market_insight_snapshot(snapshot_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_market_insight_trade_item_trade
        FOREIGN KEY (trade_id, trade_deal_date)
        REFERENCES public.trade(id, deal_date)
        ON DELETE RESTRICT,
    CONSTRAINT fk_market_insight_trade_item_comparison_trade
        FOREIGN KEY (comparison_trade_id, comparison_trade_deal_date)
        REFERENCES public.trade(id, deal_date)
        ON DELETE RESTRICT,
    CONSTRAINT fk_market_insight_trade_item_complex
        FOREIGN KEY (complex_id)
        REFERENCES public.complex(id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_market_insight_trade_item_metric
        CHECK (metric_type IN (
            'DAILY_NEW_TRADE', 'DAILY_HIGHEST_DEAL', 'WEEKLY_HIGHEST_DEAL',
            'AREA_RECORD_HIGH', 'AREA_PREVIOUS_RISE', 'AREA_PREVIOUS_FALL',
            'WEEKLY_DISCLOSURE_ACTIVITY', 'CANCELLATION_CORRECTION',
            'TRADE_ACTIVITY_30D', 'AREA_MOMENTUM_30D',
            'AREA_PRICE_RISE_90D', 'AREA_PRICE_FALL_90D',
            'TRADE_VOLUME_RISE_90D', 'TRADE_VOLUME_FALL_90D',
            'REGION_OBSERVED_CHANGE_MONTHLY'
        )),
    CONSTRAINT ck_market_insight_trade_item_rank CHECK (rank BETWEEN 1 AND 50),
    CONSTRAINT ck_market_insight_trade_item_trade_pair
        CHECK ((trade_id IS NULL AND trade_deal_date IS NULL)
            OR (trade_id IS NOT NULL AND trade_deal_date IS NOT NULL)),
    CONSTRAINT ck_market_insight_trade_item_comparison_pair
        CHECK ((comparison_trade_id IS NULL AND comparison_trade_deal_date IS NULL)
            OR (comparison_trade_id IS NOT NULL AND comparison_trade_deal_date IS NOT NULL)),
    CONSTRAINT ck_market_insight_trade_item_status
        CHECK (captured_trade_status IN ('ACTIVE', 'CANCELED'))
);

CREATE INDEX ix_market_insight_trade_item_trade
    ON public.market_insight_trade_item (trade_id, trade_deal_date)
    WHERE trade_id IS NOT NULL;

CREATE INDEX ix_market_insight_trade_item_complex
    ON public.market_insight_trade_item (complex_id, snapshot_id);
