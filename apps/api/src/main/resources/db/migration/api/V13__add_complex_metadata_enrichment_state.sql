ALTER TABLE complex
    ADD COLUMN metadata_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN metadata_source VARCHAR(32),
    ADD COLUMN metadata_checked_at TIMESTAMPTZ,
    ADD COLUMN metadata_failure_reason TEXT;

ALTER TABLE complex
    ADD CONSTRAINT ck_complex_metadata_status
    CHECK (metadata_status IN ('PENDING', 'RESOLVED', 'AMBIGUOUS', 'UNAVAILABLE', 'FAILED'));

UPDATE complex
SET metadata_status = 'RESOLVED',
    metadata_source = COALESCE(metadata_source, 'LEGACY'),
    metadata_checked_at = COALESCE(metadata_checked_at, now())
WHERE dong_cnt IS NOT NULL
   OR unit_cnt IS NOT NULL
   OR plat_area IS NOT NULL
   OR arch_area IS NOT NULL
   OR tot_area IS NOT NULL
   OR bc_rat IS NOT NULL
   OR vl_rat IS NOT NULL
   OR use_date IS NOT NULL;

CREATE INDEX ix_complex_metadata_pending ON complex (id) WHERE metadata_status = 'PENDING';
