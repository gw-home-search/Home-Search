SET LOCAL lock_timeout = '5s';

ALTER TABLE public.building_register_collection_campaign
    DROP CONSTRAINT ck_brc_campaign_mode,
    DROP CONSTRAINT ck_brc_campaign_strategy;

ALTER TABLE public.building_register_collection_campaign
    ADD COLUMN purpose character varying(32),
    ADD COLUMN target_scope character varying(32),
    ADD COLUMN selection_seed character varying(200),
    ADD COLUMN sample_size integer,
    ADD CONSTRAINT ck_brc_campaign_mode CHECK (mode IN ('missing', 'retry', 'profile')),
    ADD CONSTRAINT ck_brc_campaign_strategy CHECK (
        strategy IN ('ADAPTIVE', 'FULL_HIERARCHY', 'COMPARE_RECAP_TITLE')
    ),
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
            AND target_scope = 'VALIDATION_SAMPLE'
            AND selection_seed IS NOT NULL
            AND btrim(selection_seed) <> ''
            AND sample_size > 0)
    );

CREATE TABLE public.building_register_profile_sample_stratum (
    collection_id uuid NOT NULL REFERENCES public.building_register_collection_campaign(collection_id),
    stratum character varying(40) NOT NULL,
    population_count integer NOT NULL,
    sample_count integer NOT NULL,
    selection_seed character varying(200) NOT NULL,
    sampling_weight numeric(20,10) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (collection_id, stratum),
    CONSTRAINT ck_brpss_stratum CHECK (stratum IN (
        'SHARED_PNU', 'LEGAL_CODE_TRANSITION', 'HIERARCHY_RISK',
        'HIGH_COMPLEXITY', 'METADATA_CONTROL', 'REGIONAL_PROPORTIONAL'
    )),
    CONSTRAINT ck_brpss_counts CHECK (
        population_count >= 0 AND sample_count >= 0 AND sample_count <= population_count
    ),
    CONSTRAINT ck_brpss_weight CHECK (
        sampling_weight > 0
        AND (sample_count = 0 OR abs(sampling_weight - population_count::numeric / sample_count) < 0.0000000001)
    ),
    CONSTRAINT ck_brpss_seed CHECK (btrim(selection_seed) <> '')
);

CREATE TABLE public.building_register_profile_sample_pnu (
    collection_id uuid NOT NULL REFERENCES public.building_register_collection_campaign(collection_id),
    pnu character varying(19) NOT NULL,
    stratum character varying(40) NOT NULL,
    seed_rank bigint NOT NULL,
    sampling_weight numeric(20,10) NOT NULL,
    complex_count integer NOT NULL,
    collection_status character varying(24) DEFAULT 'PENDING' NOT NULL,
    failure_status character varying(32),
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (collection_id, pnu),
    CONSTRAINT fk_brpsp_stratum FOREIGN KEY (collection_id, stratum)
        REFERENCES public.building_register_profile_sample_stratum(collection_id, stratum),
    CONSTRAINT ck_brpsp_pnu CHECK (pnu ~ '^[0-9]{19}$'),
    CONSTRAINT ck_brpsp_rank CHECK (seed_rank >= 0),
    CONSTRAINT ck_brpsp_weight CHECK (sampling_weight > 0),
    CONSTRAINT ck_brpsp_complex_count CHECK (complex_count > 0),
    CONSTRAINT ck_brpsp_status CHECK (collection_status IN ('PENDING', 'COLLECTED', 'FAILED')),
    CONSTRAINT ck_brpsp_completion CHECK (
        (collection_status = 'COLLECTED' AND completed_at IS NOT NULL AND failure_status IS NULL)
        OR (collection_status = 'FAILED' AND completed_at IS NULL AND failure_status IS NOT NULL)
        OR (collection_status = 'PENDING' AND completed_at IS NULL AND failure_status IS NULL)
    )
);

CREATE TABLE public.building_register_profile_parse_run (
    parse_run_id uuid PRIMARY KEY,
    source_collection_id uuid NOT NULL REFERENCES public.building_register_collection_campaign(collection_id),
    parser_version character varying(80) NOT NULL,
    status character varying(24) NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    page_count integer DEFAULT 0 NOT NULL,
    record_count bigint DEFAULT 0 NOT NULL,
    failure_reason text,
    CONSTRAINT uq_brppr_source_version UNIQUE (parse_run_id, source_collection_id, parser_version),
    CONSTRAINT ck_brppr_version CHECK (btrim(parser_version) <> ''),
    CONSTRAINT ck_brppr_status CHECK (status IN ('CREATED', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_brppr_counts CHECK (page_count >= 0 AND record_count >= 0),
    CONSTRAINT ck_brppr_completion CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL AND failure_reason IS NULL)
        OR (status = 'FAILED' AND completed_at IS NULL AND failure_reason IS NOT NULL)
        OR (status IN ('CREATED', 'RUNNING') AND completed_at IS NULL AND failure_reason IS NULL)
    )
);

CREATE TABLE public.building_register_profile_parse_page (
    parse_run_id uuid NOT NULL REFERENCES public.building_register_profile_parse_run(parse_run_id),
    raw_page_id bigint NOT NULL REFERENCES public.building_register_raw_page(id),
    status character varying(24) NOT NULL,
    provider_status character varying(32),
    total_count integer,
    record_count integer DEFAULT 0 NOT NULL,
    failure_reason text,
    parsed_at timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (parse_run_id, raw_page_id),
    CONSTRAINT ck_brpp_status CHECK (status IN ('PARSED', 'EMPTY', 'PROVIDER_FAILED', 'PARSE_FAILED')),
    CONSTRAINT ck_brpp_counts CHECK (
        (total_count IS NULL OR total_count >= 0) AND record_count >= 0
    ),
    CONSTRAINT ck_brpp_failure CHECK (
        (status IN ('PARSED', 'EMPTY') AND failure_reason IS NULL)
        OR (status IN ('PROVIDER_FAILED', 'PARSE_FAILED') AND failure_reason IS NOT NULL)
    )
);

CREATE TABLE public.building_register_profile_record (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    parse_run_id uuid NOT NULL,
    raw_page_id bigint NOT NULL,
    item_index integer NOT NULL,
    pnu character varying(19) NOT NULL,
    endpoint character varying(24) NOT NULL,
    mgm_bldrgst_pk character varying(255),
    mgm_up_bldrgst_pk character varying(255),
    regstr_kind_cd character varying(8),
    content_sha256 character varying(64),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT fk_brpr_parse_page FOREIGN KEY (parse_run_id, raw_page_id)
        REFERENCES public.building_register_profile_parse_page(parse_run_id, raw_page_id),
    CONSTRAINT uq_brpr_versioned_item UNIQUE (parse_run_id, raw_page_id, item_index),
    CONSTRAINT ck_brpr_item CHECK (item_index >= 0),
    CONSTRAINT ck_brpr_pnu CHECK (pnu ~ '^[0-9]{19}$'),
    CONSTRAINT ck_brpr_endpoint CHECK (endpoint IN ('RECAP_TITLE', 'TITLE', 'BASIC_OVERVIEW')),
    CONSTRAINT ck_brpr_key CHECK (mgm_bldrgst_pk IS NULL OR btrim(mgm_bldrgst_pk) <> ''),
    CONSTRAINT ck_brpr_parent CHECK (mgm_up_bldrgst_pk IS NULL OR btrim(mgm_up_bldrgst_pk) <> ''),
    CONSTRAINT ck_brpr_hash CHECK (content_sha256 IS NULL OR content_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE public.building_register_profile_value (
    profile_record_id bigint NOT NULL REFERENCES public.building_register_profile_record(id),
    field_id character varying(64) NOT NULL,
    field_scope character varying(16) NOT NULL DEFAULT 'HIERARCHY',
    aggregation_method character varying(16) NOT NULL DEFAULT 'DIRECT',
    zero_policy character varying(24) NOT NULL DEFAULT 'VALID',
    value_type character varying(16) NOT NULL,
    value_state character varying(16) NOT NULL,
    raw_value text,
    text_value text,
    decimal_value numeric(30,12),
    integer_value bigint,
    date_value date,
    boolean_value boolean,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (profile_record_id, field_id),
    CONSTRAINT ck_brpv_field CHECK (field_id ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    CONSTRAINT ck_brpv_scope CHECK (field_scope IN ('SITE', 'BUILDING', 'HIERARCHY')),
    CONSTRAINT ck_brpv_aggregation CHECK (
        aggregation_method IN ('DIRECT', 'SUM', 'MAX', 'CONSENSUS', 'SET', 'RECALCULATED')
    ),
    CONSTRAINT ck_brpv_zero_policy CHECK (zero_policy IN ('VALID', 'MISSING_EQUIVALENT', 'INVALID')),
    CONSTRAINT ck_brpv_type CHECK (value_type IN ('TEXT', 'DECIMAL', 'INTEGER', 'DATE', 'BOOLEAN')),
    CONSTRAINT ck_brpv_state CHECK (
        value_state IN ('ABSENT', 'NULL', 'BLANK', 'ZERO', 'POSITIVE', 'VALID', 'INVALID')
    ),
    CONSTRAINT ck_brpv_single_typed_value CHECK (
        num_nonnulls(text_value, decimal_value, integer_value, date_value, boolean_value) <= 1
    ),
    CONSTRAINT ck_brpv_state_value CHECK (
        (value_state IN ('ABSENT', 'NULL', 'BLANK', 'INVALID')
            AND num_nonnulls(text_value, decimal_value, integer_value, date_value, boolean_value) = 0)
        OR (value_state IN ('ZERO', 'POSITIVE', 'VALID')
            AND num_nonnulls(text_value, decimal_value, integer_value, date_value, boolean_value) = 1)
    )
);

CREATE TABLE public.building_register_profile_schema_observation (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    parse_run_id uuid NOT NULL REFERENCES public.building_register_profile_parse_run(parse_run_id),
    raw_page_id bigint REFERENCES public.building_register_raw_page(id),
    endpoint character varying(24) NOT NULL,
    observation_type character varying(24) NOT NULL,
    source_key character varying(200),
    field_id character varying(64),
    observed_type character varying(32),
    occurrence_count bigint DEFAULT 1 NOT NULL,
    sample_value_sha256 character varying(64),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uq_brpso_observation UNIQUE NULLS NOT DISTINCT (
        parse_run_id, raw_page_id, endpoint, observation_type, source_key, field_id, observed_type
    ),
    CONSTRAINT ck_brpso_endpoint CHECK (endpoint IN ('RECAP_TITLE', 'TITLE', 'BASIC_OVERVIEW')),
    CONSTRAINT ck_brpso_type CHECK (
        observation_type IN ('UNKNOWN_KEY', 'TYPE_DRIFT', 'MISSING_REQUIRED', 'PARSE_ERROR')
    ),
    CONSTRAINT ck_brpso_count CHECK (occurrence_count > 0),
    CONSTRAINT ck_brpso_hash CHECK (
        sample_value_sha256 IS NULL OR sample_value_sha256 ~ '^[0-9a-f]{64}$'
    )
);

CREATE TABLE public.building_register_profile_hierarchy_reason (
    collection_id uuid NOT NULL REFERENCES public.building_register_collection_campaign(collection_id),
    pnu character varying(19) NOT NULL,
    reason character varying(40) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (collection_id, pnu, reason),
    CONSTRAINT ck_brphr_pnu CHECK (pnu ~ '^[0-9]{19}$'),
    CONSTRAINT ck_brphr_reason CHECK (reason IN (
        'MULTIPLE_COMPLEXES', 'MULTIPLE_RECAP_ROOTS', 'TITLES_WITHOUT_RECAP',
        'MISSING_PARENT', 'PARENT_CONFLICT', 'AMBIGUOUS_GENERATION', 'UNASSIGNABLE_TITLE'
    ))
);

CREATE TABLE public.building_register_profile_scope_assignment (
    analysis_run_id uuid NOT NULL,
    profile_record_id bigint NOT NULL REFERENCES public.building_register_profile_record(id),
    root_management_key character varying(255),
    scope_key character varying(255),
    status character varying(32) NOT NULL,
    assignment_reason character varying(80),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (analysis_run_id, profile_record_id),
    CONSTRAINT ck_brpsa_status CHECK (status IN (
        'RESOLVED', 'SHARED_SCOPE', 'INCOMPLETE_HIERARCHY', 'SOURCE_CONFLICT',
        'AMBIGUOUS_GENERATION', 'ORPHAN', 'SOURCE_MISSING'
    )),
    CONSTRAINT ck_brpsa_resolved CHECK (
        status <> 'RESOLVED' OR (root_management_key IS NOT NULL AND scope_key IS NOT NULL)
    )
);

CREATE TABLE public.building_register_profile_complex_match (
    analysis_run_id uuid NOT NULL,
    collection_id uuid NOT NULL REFERENCES public.building_register_collection_campaign(collection_id),
    complex_id bigint NOT NULL REFERENCES public.complex(id),
    pnu character varying(19) NOT NULL,
    scope_key character varying(255),
    status character varying(32) NOT NULL,
    projectable boolean DEFAULT false NOT NULL,
    failure_reason character varying(200),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (analysis_run_id, complex_id),
    CONSTRAINT ck_brpcm_pnu CHECK (pnu ~ '^[0-9]{19}$'),
    CONSTRAINT ck_brpcm_status CHECK (status IN (
        'RESOLVED', 'SHARED_SCOPE', 'INCOMPLETE_HIERARCHY', 'SOURCE_CONFLICT',
        'AMBIGUOUS_GENERATION', 'ORPHAN', 'SOURCE_MISSING'
    )),
    CONSTRAINT ck_brpcm_projectable CHECK (NOT projectable OR status = 'RESOLVED')
);

CREATE TABLE public.legal_dong_code_import (
    import_id uuid PRIMARY KEY,
    effective_date date NOT NULL,
    source_sha256 character varying(64) NOT NULL,
    source_name character varying(300) NOT NULL,
    status character varying(16) NOT NULL,
    row_count integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT uq_ldci_source UNIQUE (effective_date, source_sha256),
    CONSTRAINT ck_ldci_hash CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ldci_status CHECK (status IN ('IMPORTING', 'COMPLETED', 'REJECTED')),
    CONSTRAINT ck_ldci_rows CHECK (row_count >= 0)
);

CREATE TABLE public.legal_dong_code_mapping (
    import_id uuid NOT NULL REFERENCES public.legal_dong_code_import(import_id),
    old_legal_dong_code character varying(10) NOT NULL,
    new_legal_dong_code character varying(10) NOT NULL,
    effective_date date NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (import_id, old_legal_dong_code),
    CONSTRAINT uq_ldcm_new UNIQUE (import_id, old_legal_dong_code, new_legal_dong_code),
    CONSTRAINT ck_ldcm_old CHECK (old_legal_dong_code ~ '^[0-9]{10}$'),
    CONSTRAINT ck_ldcm_new CHECK (new_legal_dong_code ~ '^[0-9]{10}$'),
    CONSTRAINT ck_ldcm_change CHECK (old_legal_dong_code <> new_legal_dong_code)
);

CREATE TABLE public.building_register_profile_code_lookup (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    collection_id uuid NOT NULL REFERENCES public.building_register_collection_campaign(collection_id),
    import_id uuid NOT NULL REFERENCES public.legal_dong_code_import(import_id),
    request_id uuid NOT NULL,
    original_pnu character varying(19) NOT NULL,
    candidate_pnu character varying(19) NOT NULL,
    old_result character varying(24) NOT NULL,
    new_result character varying(24) NOT NULL,
    comparison_status character varying(40) NOT NULL,
    old_management_key_hashes jsonb DEFAULT '[]'::jsonb NOT NULL,
    new_management_key_hashes jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uq_brpcl_identity UNIQUE (collection_id, import_id, original_pnu, request_id),
    CONSTRAINT ck_brpcl_original CHECK (original_pnu ~ '^[0-9]{19}$'),
    CONSTRAINT ck_brpcl_candidate CHECK (candidate_pnu ~ '^[0-9]{19}$'),
    CONSTRAINT ck_brpcl_result CHECK (
        old_result IN ('SUCCESS', 'EMPTY', 'PROVIDER_FAILED', 'PARSE_FAILED')
        AND new_result IN ('SUCCESS', 'EMPTY', 'PROVIDER_FAILED', 'PARSE_FAILED')
    ),
    CONSTRAINT ck_brpcl_status CHECK (comparison_status IN (
        'CODE_TRANSITION_EQUIVALENT', 'OLD_ONLY_SUCCESS', 'NEW_ONLY_SUCCESS',
        'BOTH_DIFFERENT', 'BOTH_EMPTY', 'NOT_COMPARABLE_PROVIDER_FAILURE'
    ))
);

CREATE TABLE public.building_register_profile_analysis_run (
    analysis_run_id uuid PRIMARY KEY,
    collection_id uuid NOT NULL REFERENCES public.building_register_collection_campaign(collection_id),
    parse_run_id uuid NOT NULL REFERENCES public.building_register_profile_parse_run(parse_run_id),
    rules_version character varying(80) NOT NULL,
    status character varying(16) NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    report_manifest jsonb,
    failure_reason text,
    CONSTRAINT uq_brpar_inputs UNIQUE (analysis_run_id, collection_id, parse_run_id, rules_version),
    CONSTRAINT ck_brpar_rules CHECK (btrim(rules_version) <> ''),
    CONSTRAINT ck_brpar_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_brpar_completion CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL AND failure_reason IS NULL)
        OR (status = 'FAILED' AND completed_at IS NULL AND failure_reason IS NOT NULL)
        OR (status = 'RUNNING' AND completed_at IS NULL AND failure_reason IS NULL)
    )
);

ALTER TABLE public.building_register_profile_scope_assignment
    ADD CONSTRAINT fk_brpsa_analysis FOREIGN KEY (analysis_run_id)
        REFERENCES public.building_register_profile_analysis_run(analysis_run_id);

ALTER TABLE public.building_register_profile_complex_match
    ADD CONSTRAINT fk_brpcm_analysis FOREIGN KEY (analysis_run_id)
        REFERENCES public.building_register_profile_analysis_run(analysis_run_id);

CREATE TABLE public.building_register_profile_comparison (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    analysis_run_id uuid NOT NULL REFERENCES public.building_register_profile_analysis_run(analysis_run_id),
    pnu_scope_hash character varying(64) NOT NULL,
    field_id character varying(64) NOT NULL,
    aggregation_method character varying(16) NOT NULL,
    status character varying(32) NOT NULL,
    recap_value jsonb,
    title_value jsonb,
    difference numeric(30,12),
    contributor_count integer DEFAULT 0 NOT NULL,
    expected_contributor_count integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uq_brpc_field_scope UNIQUE (analysis_run_id, pnu_scope_hash, field_id, aggregation_method),
    CONSTRAINT ck_brpc_hash CHECK (pnu_scope_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_brpc_method CHECK (
        aggregation_method IN ('DIRECT', 'SUM', 'MAX', 'CONSENSUS', 'SET', 'RECALCULATED')
    ),
    CONSTRAINT ck_brpc_status CHECK (status IN (
        'MATCH', 'WITHIN_TOLERANCE', 'CONFLICT', 'INCOMPLETE', 'NOT_COMPARABLE'
    )),
    CONSTRAINT ck_brpc_counts CHECK (
        contributor_count >= 0 AND expected_contributor_count >= 0
        AND contributor_count <= expected_contributor_count
    )
);

CREATE TABLE public.building_register_profile_field_quality (
    analysis_run_id uuid NOT NULL REFERENCES public.building_register_profile_analysis_run(analysis_run_id),
    field_id character varying(64) NOT NULL,
    field_scope character varying(16) NOT NULL,
    stratum character varying(40) NOT NULL DEFAULT 'WEIGHTED_NATIONAL',
    source_record_coverage numeric(12,10) NOT NULL,
    building_coverage numeric(12,10) NOT NULL,
    pnu_coverage numeric(12,10) NOT NULL,
    projectable_complex_readiness numeric(12,10) NOT NULL,
    operational_completion numeric(12,10) NOT NULL,
    invalid_rate numeric(12,10) NOT NULL,
    conflict_rate numeric(12,10) NOT NULL,
    wilson_low numeric(12,10),
    wilson_high numeric(12,10),
    quality_tier character varying(24) NOT NULL,
    meaning_verified boolean DEFAULT false NOT NULL,
    numerator numeric(20,6) NOT NULL,
    denominator numeric(20,6) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (analysis_run_id, field_id, stratum),
    CONSTRAINT ck_brpfq_scope CHECK (field_scope IN ('SITE', 'BUILDING', 'HIERARCHY')),
    CONSTRAINT ck_brpfq_rates CHECK (
        source_record_coverage BETWEEN 0 AND 1
        AND building_coverage BETWEEN 0 AND 1
        AND pnu_coverage BETWEEN 0 AND 1
        AND projectable_complex_readiness BETWEEN 0 AND 1
        AND operational_completion BETWEEN 0 AND 1
        AND invalid_rate BETWEEN 0 AND 1
        AND conflict_rate BETWEEN 0 AND 1
        AND (wilson_low IS NULL OR wilson_low BETWEEN 0 AND 1)
        AND (wilson_high IS NULL OR wilson_high BETWEEN 0 AND 1)
    ),
    CONSTRAINT ck_brpfq_tier CHECK (quality_tier IN (
        'PROMOTE_CANDIDATE', 'RETAIN_PROFILE', 'RAW_ONLY', 'REJECT_FOR_PROJECTION'
    )),
    CONSTRAINT ck_brpfq_denominator CHECK (
        numerator >= 0 AND denominator >= 0 AND numerator <= denominator
    )
);

CREATE INDEX ix_brpsp_stratum ON public.building_register_profile_sample_pnu(collection_id, stratum);
CREATE INDEX ix_brpp_status ON public.building_register_profile_parse_page(parse_run_id, status);
CREATE INDEX ix_brpr_pnu_endpoint ON public.building_register_profile_record(parse_run_id, pnu, endpoint);
CREATE INDEX ix_brpr_management_key ON public.building_register_profile_record(parse_run_id, mgm_bldrgst_pk)
    WHERE mgm_bldrgst_pk IS NOT NULL;
CREATE INDEX ix_brpv_field_state ON public.building_register_profile_value(field_id, value_state);
CREATE INDEX ix_brpso_type_key ON public.building_register_profile_schema_observation(parse_run_id, observation_type, source_key);
CREATE INDEX ix_brpcm_collection_status ON public.building_register_profile_complex_match(collection_id, status);
CREATE INDEX ix_brpc_analysis_field ON public.building_register_profile_comparison(analysis_run_id, field_id, status);

DO $precondition$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'home_search_property_runtime') THEN
        RAISE EXCEPTION
            'V13 requires the home_search_property_runtime role to be bootstrapped before migration';
    END IF;
END
$precondition$;

GRANT SELECT, INSERT, UPDATE ON TABLE
    public.building_register_profile_sample_stratum,
    public.building_register_profile_sample_pnu,
    public.building_register_profile_parse_run,
    public.building_register_profile_parse_page,
    public.building_register_profile_record,
    public.building_register_profile_value,
    public.building_register_profile_schema_observation,
    public.building_register_profile_hierarchy_reason,
    public.building_register_profile_scope_assignment,
    public.building_register_profile_complex_match,
    public.legal_dong_code_import,
    public.legal_dong_code_mapping,
    public.building_register_profile_code_lookup,
    public.building_register_profile_analysis_run,
    public.building_register_profile_comparison,
    public.building_register_profile_field_quality
TO home_search_property_runtime;

GRANT USAGE, SELECT ON SEQUENCE
    public.building_register_profile_record_id_seq,
    public.building_register_profile_schema_observation_id_seq,
    public.building_register_profile_code_lookup_id_seq,
    public.building_register_profile_comparison_id_seq
TO home_search_property_runtime;
