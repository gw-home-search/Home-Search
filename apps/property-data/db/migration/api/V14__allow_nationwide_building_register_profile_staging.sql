SET LOCAL lock_timeout = '5s';

ALTER TABLE public.building_register_collection_campaign
    DROP CONSTRAINT ck_brc_campaign_profile_config,
    ADD CONSTRAINT ck_brc_campaign_profile_config CHECK (
        (mode <> 'profile'
            AND purpose IS NULL
            AND target_scope IS NULL
            AND selection_seed IS NULL
            AND sample_size IS NULL)
        OR
        (mode = 'profile'
            AND strategy = 'COMPARE_RECAP_TITLE'
            AND purpose = 'PROFILE_DISCOVERY'
            AND selection_seed IS NOT NULL
            AND btrim(selection_seed) <> ''
            AND (
                (target_scope = 'VALIDATION_SAMPLE' AND sample_size > 0)
                OR
                (target_scope = 'NATIONWIDE_STAGING' AND sample_size > 0)
            ))
    );

ALTER TABLE public.building_register_profile_sample_stratum
    DROP CONSTRAINT ck_brpss_stratum,
    ADD CONSTRAINT ck_brpss_stratum CHECK (stratum IN (
        'SHARED_PNU', 'LEGAL_CODE_TRANSITION', 'HIERARCHY_RISK',
        'HIGH_COMPLEXITY', 'METADATA_CONTROL', 'REGIONAL_PROPORTIONAL',
        'NATIONWIDE_CENSUS'
    ));
