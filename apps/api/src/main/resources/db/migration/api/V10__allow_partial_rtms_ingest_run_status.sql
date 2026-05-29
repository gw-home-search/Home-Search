ALTER TABLE rtms_ingest_run
    DROP CONSTRAINT IF EXISTS ck_rtms_ingest_run_status;

ALTER TABLE rtms_ingest_run
    ADD CONSTRAINT ck_rtms_ingest_run_status
        CHECK (status IN ('COMPLETED', 'FAILED', 'PARTIAL'));
