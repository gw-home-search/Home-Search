ALTER TABLE dataset_acquisition
    DROP CONSTRAINT dataset_acquisition_source_raw_unique;

UPDATE dataset_acquisition acquisition
SET normalization_schema_version = contract.contract ->> 'schema_version'
FROM dataset_source_contract contract
WHERE acquisition.contract_id = contract.contract_id
  AND acquisition.normalization_schema_version IS NULL;

ALTER TABLE dataset_acquisition
    ALTER COLUMN normalization_schema_version SET NOT NULL,
    ADD CONSTRAINT dataset_acquisition_source_raw_schema_unique
        UNIQUE (source_id, checksum, normalization_schema_version);
