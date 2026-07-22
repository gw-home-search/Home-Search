CREATE TABLE public.rtms_collection_execution (
    execution_id uuid PRIMARY KEY,
    collection_mode varchar(32) NOT NULL,
    scope_type varchar(32) NOT NULL,
    run_date date NOT NULL,
    state varchar(32) NOT NULL,
    planned_work_unit_count integer NOT NULL,
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    failure_reason varchar(500),
    CONSTRAINT ck_rtms_collection_execution_mode
        CHECK (collection_mode IN ('DAILY', 'BACKFILL', 'REPLAY', 'MAINTENANCE')),
    CONSTRAINT ck_rtms_collection_execution_scope
        CHECK (scope_type IN ('NATIONWIDE', 'TARGETED')),
    CONSTRAINT ck_rtms_collection_execution_state
        CHECK (state IN ('PLANNED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_rtms_collection_execution_planned_count
        CHECK (planned_work_unit_count > 0),
    CONSTRAINT ck_rtms_collection_execution_completed_at
        CHECK ((state IN ('PLANNED', 'RUNNING') AND completed_at IS NULL)
            OR (state IN ('COMPLETED', 'PARTIAL', 'FAILED') AND completed_at IS NOT NULL))
);

CREATE INDEX ix_rtms_collection_execution_daily_run
    ON public.rtms_collection_execution (run_date DESC, completed_at DESC)
    WHERE collection_mode = 'DAILY';

CREATE TABLE public.rtms_collection_work_unit (
    execution_id uuid NOT NULL,
    lawd_cd varchar(16) NOT NULL,
    deal_ymd varchar(8) NOT NULL,
    state varchar(32) NOT NULL,
    rtms_ingest_run_id bigint,
    started_at timestamptz,
    completed_at timestamptz,
    PRIMARY KEY (execution_id, lawd_cd, deal_ymd),
    CONSTRAINT fk_rtms_collection_work_unit_execution
        FOREIGN KEY (execution_id)
        REFERENCES public.rtms_collection_execution(execution_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_rtms_collection_work_unit_ingest_run
        FOREIGN KEY (rtms_ingest_run_id)
        REFERENCES public.rtms_ingest_run(id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_rtms_collection_work_unit_state
        CHECK (state IN ('PLANNED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_rtms_collection_work_unit_terminal
        CHECK ((state IN ('PLANNED', 'RUNNING') AND completed_at IS NULL)
            OR (state IN ('COMPLETED', 'PARTIAL', 'FAILED')
                AND completed_at IS NOT NULL
                AND rtms_ingest_run_id IS NOT NULL))
);

CREATE INDEX ix_rtms_collection_work_unit_state
    ON public.rtms_collection_work_unit (execution_id, state, lawd_cd, deal_ymd);

ALTER TABLE public.raw_trade_ingest
    ADD COLUMN execution_correlation_id uuid;

ALTER TABLE public.raw_trade_ingest
    ADD CONSTRAINT fk_raw_trade_ingest_collection_execution
    FOREIGN KEY (execution_correlation_id)
    REFERENCES public.rtms_collection_execution(execution_id)
    ON DELETE RESTRICT;

CREATE INDEX ix_raw_trade_ingest_execution_status_processed
    ON public.raw_trade_ingest (execution_correlation_id, status, processed_at, id)
    WHERE execution_correlation_id IS NOT NULL;
