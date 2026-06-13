-- Allows the name-tiebreaker lookup paths added for same-PNU ODC candidate disambiguation.
-- ComplexMetadataLookupPath now includes CANONICAL_PNU_NAME and APPROVED_PREFIX_ALIAS_NAME,
-- so the enrichment-attempt evidence CHECK constraint must accept them.

ALTER TABLE complex_metadata_enrichment_attempt
    DROP CONSTRAINT ck_cmea_lookup_path;

ALTER TABLE complex_metadata_enrichment_attempt
    ADD CONSTRAINT ck_cmea_lookup_path CHECK (
        lookup_path IS NULL
        OR lookup_path IN (
            'CANONICAL_PNU',
            'CANONICAL_PNU_NAME',
            'APPROVED_PREFIX_ALIAS',
            'APPROVED_PREFIX_ALIAS_NAME',
            'COMPLEX_PK_DIAGNOSTIC',
            'BUILDING_PNU',
            'NONE'
        )
    );
