ALTER TABLE rtms_ingest_run
    DROP CONSTRAINT rtms_ingest_run_status_check;

ALTER TABLE rtms_ingest_run
    ADD CONSTRAINT ck_rtms_ingest_run_status
        CHECK (status IN ('COMPLETED', 'FAILED'));
