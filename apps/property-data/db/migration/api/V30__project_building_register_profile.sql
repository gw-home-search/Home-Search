CREATE TABLE public.building_register_profile_projection_run (
    projection_run_id uuid PRIMARY KEY,
    analysis_run_id uuid NOT NULL REFERENCES public.building_register_profile_analysis_run(analysis_run_id),
    collection_id uuid NOT NULL REFERENCES public.building_register_collection_campaign(collection_id),
    parse_run_id uuid NOT NULL REFERENCES public.building_register_profile_parse_run(parse_run_id),
    projection_version character varying(80) NOT NULL,
    minimum_readiness numeric(12,10) NOT NULL,
    status character varying(16) NOT NULL,
    complex_snapshot_sha256 character varying(64),
    eligible_field_count integer DEFAULT 0 NOT NULL,
    complex_count integer DEFAULT 0 NOT NULL,
    projectable_complex_count integer DEFAULT 0 NOT NULL,
    building_count integer DEFAULT 0 NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    failure_reason text,
    CONSTRAINT uq_brpprj_inputs UNIQUE (analysis_run_id, projection_version, minimum_readiness),
    CONSTRAINT ck_brpprj_version CHECK (btrim(projection_version) <> ''),
    CONSTRAINT ck_brpprj_threshold CHECK (minimum_readiness BETWEEN 0 AND 1),
    CONSTRAINT ck_brpprj_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_brpprj_hash CHECK (
        complex_snapshot_sha256 IS NULL OR complex_snapshot_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_brpprj_counts CHECK (
        eligible_field_count >= 0 AND complex_count >= 0
        AND projectable_complex_count >= 0 AND projectable_complex_count <= complex_count
        AND building_count >= 0
    ),
    CONSTRAINT ck_brpprj_completion CHECK (
        (status='COMPLETED' AND completed_at IS NOT NULL AND failure_reason IS NULL
            AND complex_snapshot_sha256 IS NOT NULL)
        OR (status='FAILED' AND completed_at IS NULL AND failure_reason IS NOT NULL)
        OR (status='RUNNING' AND completed_at IS NULL AND failure_reason IS NULL)
    )
);

CREATE TABLE public.complex_building_register_profile (
    projection_run_id uuid NOT NULL REFERENCES public.building_register_profile_projection_run(projection_run_id),
    complex_id bigint NOT NULL REFERENCES public.complex(id),
    analysis_run_id uuid NOT NULL,
    collection_id uuid NOT NULL,
    assignment_status character varying(32) NOT NULL,
    projectable boolean DEFAULT false NOT NULL,
    failure_reason character varying(200),
    source_scope_key character varying(255),
    source_root_management_key character varying(255),
    atch_bld_cnt bigint,
    bjdong_cd character varying(32),
    bld_nm text,
    bun character varying(16),
    bylot_cnt bigint,
    crtn_day date,
    fmly_cnt bigint,
    hhld_cnt bigint,
    ji character varying(16),
    new_plat_plc text,
    plat_area numeric(30,12),
    plat_gb_cd character varying(8),
    plat_plc text,
    pms_day date,
    road_bjdong_cd character varying(32),
    road_cd character varying(32),
    road_main_no character varying(16),
    road_sub_no character varying(16),
    road_underground_cd character varying(8),
    sigungu_cd character varying(16),
    stcns_day date,
    tot_dong_tot_area numeric(30,12),
    use_apr_day date,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (projection_run_id, complex_id),
    CONSTRAINT fk_cbrp_analysis FOREIGN KEY (analysis_run_id)
        REFERENCES public.building_register_profile_analysis_run(analysis_run_id),
    CONSTRAINT fk_cbrp_collection FOREIGN KEY (collection_id)
        REFERENCES public.building_register_collection_campaign(collection_id),
    CONSTRAINT ck_cbrp_status CHECK (assignment_status IN (
        'RESOLVED', 'SHARED_SCOPE', 'INCOMPLETE_HIERARCHY', 'SOURCE_CONFLICT',
        'AMBIGUOUS_GENERATION', 'ORPHAN', 'SOURCE_MISSING'
    )),
    CONSTRAINT ck_cbrp_projectable CHECK (
        (projectable AND assignment_status='RESOLVED' AND source_scope_key IS NOT NULL)
        OR NOT projectable
    ),
    CONSTRAINT ck_cbrp_counts CHECK (
        (atch_bld_cnt IS NULL OR atch_bld_cnt >= 0)
        AND (bylot_cnt IS NULL OR bylot_cnt >= 0)
        AND (fmly_cnt IS NULL OR fmly_cnt >= 0)
        AND (hhld_cnt IS NULL OR hhld_cnt >= 0)
    ),
    CONSTRAINT ck_cbrp_areas CHECK (
        (plat_area IS NULL OR plat_area >= 0)
        AND (tot_dong_tot_area IS NULL OR tot_dong_tot_area >= 0)
    )
);

CREATE TABLE public.complex_building_register_building (
    projection_run_id uuid NOT NULL,
    complex_id bigint NOT NULL,
    source_management_key character varying(255) NOT NULL,
    source_parent_management_key character varying(255),
    source_content_sha256 character varying(64),
    main_atch_gb_cd character varying(16),
    main_atch_gb_cd_nm text,
    dong_nm text,
    atch_bld_area numeric(30,12),
    ho_cnt bigint,
    indr_mech_utcnt bigint,
    indr_mech_area numeric(30,12),
    oudr_mech_utcnt bigint,
    oudr_mech_area numeric(30,12),
    indr_auto_utcnt bigint,
    indr_auto_area numeric(30,12),
    oudr_auto_utcnt bigint,
    oudr_auto_area numeric(30,12),
    heit numeric(30,12),
    grnd_flr_cnt bigint,
    ugrnd_flr_cnt bigint,
    ride_use_elvt_cnt bigint,
    emgen_use_elvt_cnt bigint,
    strct_cd character varying(32),
    strct_cd_nm text,
    etc_strct text,
    roof_cd character varying(32),
    roof_cd_nm text,
    etc_roof text,
    main_purps_cd character varying(32),
    main_purps_cd_nm text,
    etc_purps text,
    rserthqk_dsgn_apply_yn boolean,
    engr_rat numeric(30,12),
    engr_epi numeric(30,12),
    gn_bld_cert numeric(30,12),
    itg_bld_cert numeric(30,12),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (projection_run_id, complex_id, source_management_key),
    CONSTRAINT fk_cbrb_profile FOREIGN KEY (projection_run_id, complex_id)
        REFERENCES public.complex_building_register_profile(projection_run_id, complex_id),
    CONSTRAINT ck_cbrb_source_hash CHECK (
        source_content_sha256 IS NULL OR source_content_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_cbrb_counts CHECK (
        (ho_cnt IS NULL OR ho_cnt >= 0)
        AND (indr_mech_utcnt IS NULL OR indr_mech_utcnt >= 0)
        AND (oudr_mech_utcnt IS NULL OR oudr_mech_utcnt >= 0)
        AND (indr_auto_utcnt IS NULL OR indr_auto_utcnt >= 0)
        AND (oudr_auto_utcnt IS NULL OR oudr_auto_utcnt >= 0)
        AND (grnd_flr_cnt IS NULL OR grnd_flr_cnt >= 0)
        AND (ugrnd_flr_cnt IS NULL OR ugrnd_flr_cnt >= 0)
        AND (ride_use_elvt_cnt IS NULL OR ride_use_elvt_cnt >= 0)
        AND (emgen_use_elvt_cnt IS NULL OR emgen_use_elvt_cnt >= 0)
    )
);

CREATE TABLE public.building_register_profile_projected_quality (
    projection_run_id uuid NOT NULL REFERENCES public.building_register_profile_projection_run(projection_run_id),
    field_id character varying(64) NOT NULL,
    field_scope character varying(16) NOT NULL,
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
    projection_use character varying(24) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (projection_run_id, field_id),
    CONSTRAINT ck_brppq_scope CHECK (field_scope IN ('SITE', 'BUILDING')),
    CONSTRAINT ck_brppq_rates CHECK (
        source_record_coverage BETWEEN 0 AND 1
        AND building_coverage BETWEEN 0 AND 1
        AND pnu_coverage BETWEEN 0 AND 1
        AND projectable_complex_readiness BETWEEN 0 AND 1
        AND operational_completion BETWEEN 0 AND 1
        AND invalid_rate BETWEEN 0 AND 1
        AND conflict_rate BETWEEN 0 AND 1
    ),
    CONSTRAINT ck_brppq_tier CHECK (quality_tier IN (
        'PROMOTE_CANDIDATE', 'RETAIN_PROFILE', 'RAW_ONLY', 'REJECT_FOR_PROJECTION'
    )),
    CONSTRAINT ck_brppq_use CHECK (projection_use IN ('OPERATIONAL', 'PROFILE_ONLY', 'OBSERVATION_ONLY'))
);

CREATE TABLE public.building_register_profile_archive_manifest (
    archive_id uuid PRIMARY KEY,
    collection_id uuid NOT NULL REFERENCES public.building_register_collection_campaign(collection_id),
    parse_run_id uuid NOT NULL REFERENCES public.building_register_profile_parse_run(parse_run_id),
    analysis_run_id uuid NOT NULL REFERENCES public.building_register_profile_analysis_run(analysis_run_id),
    projection_run_id uuid NOT NULL REFERENCES public.building_register_profile_projection_run(projection_run_id),
    archive_uri text NOT NULL,
    archive_sha256 character varying(64) NOT NULL,
    archive_byte_count bigint NOT NULL,
    source_database_size_bytes bigint NOT NULL,
    row_counts jsonb NOT NULL,
    status character varying(24) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    verified_at timestamp with time zone,
    restore_verified_at timestamp with time zone,
    cleaned_at timestamp with time zone,
    CONSTRAINT uq_brpam_projection UNIQUE (projection_run_id),
    CONSTRAINT ck_brpam_uri CHECK (btrim(archive_uri) <> ''),
    CONSTRAINT ck_brpam_hash CHECK (archive_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_brpam_sizes CHECK (archive_byte_count > 0 AND source_database_size_bytes > 0),
    CONSTRAINT ck_brpam_rows CHECK (jsonb_typeof(row_counts)='object'),
    CONSTRAINT ck_brpam_status CHECK (status IN ('VERIFIED', 'RESTORE_VERIFIED', 'CLEANED')),
    CONSTRAINT ck_brpam_timestamps CHECK (
        verified_at IS NOT NULL
        AND (status='VERIFIED' OR restore_verified_at IS NOT NULL)
        AND (status<>'CLEANED' OR cleaned_at IS NOT NULL)
    )
);

CREATE INDEX ix_cbrp_complex ON public.complex_building_register_profile(complex_id, projection_run_id);
CREATE INDEX ix_cbrb_complex ON public.complex_building_register_building(complex_id, projection_run_id);
CREATE INDEX ix_brpprj_analysis ON public.building_register_profile_projection_run(analysis_run_id, status);
