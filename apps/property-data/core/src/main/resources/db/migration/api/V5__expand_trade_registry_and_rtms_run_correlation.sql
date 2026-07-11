ALTER TABLE public.trade_source_key_registry
    ADD COLUMN trade_deal_date date;

ALTER TABLE public.trade_source_key_registry
    ADD CONSTRAINT ck_trade_source_key_registry_trade_pair
    CHECK (
        (trade_id IS NULL AND trade_deal_date IS NULL)
        OR
        (trade_id IS NOT NULL AND trade_deal_date IS NOT NULL)
    )
    NOT VALID;

CREATE INDEX ix_trade_source_key_registry_trade_pair
    ON public.trade_source_key_registry (trade_id, trade_deal_date)
    WHERE trade_id IS NOT NULL
      AND trade_deal_date IS NOT NULL;

ALTER TABLE public.rtms_ingest_run
    ADD COLUMN execution_correlation_id uuid;

CREATE INDEX ix_rtms_ingest_run_execution_correlation
    ON public.rtms_ingest_run (execution_correlation_id, id)
    WHERE execution_correlation_id IS NOT NULL;
