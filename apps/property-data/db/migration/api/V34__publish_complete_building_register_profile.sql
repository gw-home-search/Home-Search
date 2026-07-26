CREATE TABLE public.building_register_profile_repair_run (
    collection_id uuid PRIMARY KEY REFERENCES public.building_register_collection_campaign(collection_id),
    source_collection_id uuid NOT NULL REFERENCES public.building_register_collection_campaign(collection_id),
    request_id uuid NOT NULL,
    run_date date NOT NULL,
    repair_policy_version character varying(80) NOT NULL,
    max_requests integer NOT NULL,
    parallelism integer NOT NULL,
    status character varying(16) NOT NULL,
    target_count integer DEFAULT 0 NOT NULL,
    request_count integer DEFAULT 0 NOT NULL,
    completed_count integer DEFAULT 0 NOT NULL,
    failure_count integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    failure_reason text,
    CONSTRAINT uq_brprr_inputs UNIQUE (source_collection_id,collection_id,repair_policy_version),
    CONSTRAINT ck_brprr_policy CHECK (repair_policy_version='PROFILE_REPAIR_V1'),
    CONSTRAINT ck_brprr_limits CHECK (max_requests>0 AND max_requests<=20000 AND parallelism BETWEEN 1 AND 4),
    CONSTRAINT ck_brprr_counts CHECK (
        target_count>=0 AND request_count>=0 AND completed_count>=0 AND failure_count>=0
        AND completed_count<=target_count AND failure_count<=target_count),
    CONSTRAINT ck_brprr_status CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    CONSTRAINT ck_brprr_completion CHECK (
        (status='RUNNING' AND completed_at IS NULL AND failure_reason IS NULL)
        OR (status='COMPLETED' AND completed_at IS NOT NULL AND failure_reason IS NULL)
        OR (status='FAILED' AND completed_at IS NULL AND failure_reason IS NOT NULL))
);

CREATE TABLE public.building_register_profile_publication (
    publication_id uuid PRIMARY KEY,
    source_collection_id uuid NOT NULL REFERENCES public.building_register_collection_campaign(collection_id),
    source_parse_run_id uuid NOT NULL REFERENCES public.building_register_profile_parse_run(parse_run_id),
    source_analysis_run_id uuid NOT NULL REFERENCES public.building_register_profile_analysis_run(analysis_run_id),
    source_projection_run_id uuid NOT NULL REFERENCES public.building_register_profile_projection_run(projection_run_id),
    rules_version character varying(80) NOT NULL,
    parser_version character varying(80) NOT NULL,
    status character varying(16) NOT NULL,
    expected_site_count integer NOT NULL,
    expected_building_count integer NOT NULL,
    expected_hierarchy_count integer NOT NULL,
    expected_evidence_count integer NOT NULL,
    expected_summary_count integer NOT NULL,
    site_count integer DEFAULT 0 NOT NULL,
    building_count integer DEFAULT 0 NOT NULL,
    hierarchy_count integer DEFAULT 0 NOT NULL,
    evidence_count integer DEFAULT 0 NOT NULL,
    summary_count integer DEFAULT 0 NOT NULL,
    content_sha256 character varying(64),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    validated_at timestamp with time zone,
    published_at timestamp with time zone,
    superseded_at timestamp with time zone,
    failure_reason text,
    CONSTRAINT uq_brpp_inputs UNIQUE (source_analysis_run_id, rules_version),
    CONSTRAINT ck_brpp_versions CHECK (btrim(rules_version) <> '' AND btrim(parser_version) <> ''),
    CONSTRAINT ck_brpp_status CHECK (status IN ('PREPARING','VALIDATED','PUBLISHED','SUPERSEDED','FAILED')),
    CONSTRAINT ck_brpp_counts CHECK (
        expected_site_count >= 0 AND expected_building_count >= 0
        AND expected_hierarchy_count >= 0 AND expected_evidence_count >= 0
        AND expected_summary_count >= 0 AND site_count >= 0 AND building_count >= 0
        AND hierarchy_count >= 0 AND evidence_count >= 0 AND summary_count >= 0),
    CONSTRAINT ck_brpp_digest CHECK (content_sha256 IS NULL OR content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_brpp_state CHECK (
        (status='PREPARING' AND validated_at IS NULL AND published_at IS NULL
            AND superseded_at IS NULL AND failure_reason IS NULL)
        OR (status='VALIDATED' AND validated_at IS NOT NULL AND published_at IS NULL
            AND superseded_at IS NULL AND failure_reason IS NULL AND content_sha256 IS NOT NULL)
        OR (status='PUBLISHED' AND validated_at IS NOT NULL AND published_at IS NOT NULL
            AND superseded_at IS NULL AND failure_reason IS NULL AND content_sha256 IS NOT NULL)
        OR (status='SUPERSEDED' AND validated_at IS NOT NULL AND published_at IS NOT NULL
            AND superseded_at IS NOT NULL AND failure_reason IS NULL AND content_sha256 IS NOT NULL)
        OR (status='FAILED' AND published_at IS NULL AND superseded_at IS NULL
            AND failure_reason IS NOT NULL))
);

CREATE UNIQUE INDEX uq_brpp_one_published
    ON public.building_register_profile_publication ((true)) WHERE status='PUBLISHED';

CREATE TABLE public.building_register_profile_site (
    publication_id uuid NOT NULL REFERENCES public.building_register_profile_publication(publication_id),
    pnu character varying(19) NOT NULL,
    root_management_key character varying(255) NOT NULL,
    bld_nm text,
    plat_plc text,
    new_plat_plc text,
    sigungu_cd character varying(16),
    bjdong_cd character varying(32),
    plat_gb_cd character varying(8),
    bun character varying(16),
    ji character varying(16),
    splot_nm text,
    block text,
    lot text,
    bylot_cnt bigint,
    road_cd character varying(32),
    road_bjdong_cd character varying(32),
    road_underground_cd character varying(8),
    road_main_no character varying(16),
    road_sub_no character varying(16),
    plat_area numeric(30,12),
    bc_rat numeric(18,8),
    vl_rat numeric(18,8),
    tot_dong_tot_area numeric(30,12),
    hhld_cnt bigint,
    fmly_cnt bigint,
    main_bld_cnt bigint,
    atch_bld_cnt bigint,
    tot_pkng_cnt bigint,
    pms_day date,
    stcns_day date,
    use_apr_day date,
    crtn_day date,
    pmsno_year character varying(16),
    pmsno_kik_cd character varying(32),
    pmsno_kik_cd_nm text,
    pmsno_gb_cd character varying(32),
    pmsno_gb_cd_nm text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (publication_id,pnu,root_management_key),
    CONSTRAINT ck_brps_pnu CHECK (pnu ~ '^[0-9]{19}$')
);

CREATE TABLE public.building_register_profile_building (
    publication_id uuid NOT NULL REFERENCES public.building_register_profile_publication(publication_id),
    pnu character varying(19) NOT NULL,
    management_key character varying(255) NOT NULL,
    parent_management_key character varying(255),
    main_atch_gb_cd character varying(16),
    main_atch_gb_cd_nm text,
    dong_nm text,
    arch_area numeric(30,12),
    tot_area numeric(30,12),
    vl_rat_estm_tot_area numeric(30,12),
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
    rserthqk_ability text,
    engr_grade text,
    engr_rat numeric(30,12),
    engr_epi numeric(30,12),
    gn_bld_grade text,
    gn_bld_cert numeric(30,12),
    itg_bld_grade text,
    itg_bld_cert numeric(30,12),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (publication_id,management_key),
    CONSTRAINT ck_brpb_pnu CHECK (pnu ~ '^[0-9]{19}$')
);

CREATE TABLE public.building_register_profile_hierarchy (
    publication_id uuid NOT NULL REFERENCES public.building_register_profile_publication(publication_id),
    pnu character varying(19) NOT NULL,
    source_record_key character varying(64) NOT NULL,
    mgm_bldrgst_pk text,
    mgm_up_bldrgst_pk text,
    regstr_gb_cd character varying(32),
    regstr_gb_cd_nm text,
    regstr_kind_cd character varying(32),
    regstr_kind_cd_nm text,
    new_old_regstr_gb_cd character varying(32),
    new_old_regstr_gb_cd_nm text,
    rnum bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (publication_id,pnu,source_record_key),
    CONSTRAINT ck_brph_pnu CHECK (pnu ~ '^[0-9]{19}$'),
    CONSTRAINT ck_brph_source_key CHECK (source_record_key ~ '^[0-9a-f]{64}$')
);

CREATE TABLE public.building_register_profile_field_evidence (
    evidence_id uuid PRIMARY KEY,
    publication_id uuid NOT NULL REFERENCES public.building_register_profile_publication(publication_id),
    scope character varying(16) NOT NULL,
    scope_key character varying(255) NOT NULL,
    field_id character varying(64) NOT NULL,
    value_state character varying(16) NOT NULL,
    raw_value text,
    text_value text,
    decimal_value numeric(30,12),
    integer_value bigint,
    date_value date,
    boolean_value boolean,
    source_method character varying(32) NOT NULL,
    aggregation_method character varying(24) NOT NULL,
    public_scope character varying(16),
    quality character varying(24),
    conflict_status character varying(24) NOT NULL,
    source_record_key character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uq_brpfe_source UNIQUE (publication_id,scope,scope_key,field_id,source_record_key),
    CONSTRAINT ck_brpfe_scope CHECK (scope IN ('SITE','BUILDING','HIERARCHY')),
    CONSTRAINT ck_brpfe_state CHECK (value_state IN ('ABSENT','NULL','BLANK','ZERO','POSITIVE','VALID','INVALID')),
    CONSTRAINT ck_brpfe_source CHECK (source_method IN ('COMPLEX','PNU_ROOT','TITLE_AGGREGATE','PROVIDER_DIRECT')),
    CONSTRAINT ck_brpfe_aggregation CHECK (aggregation_method IN ('DIRECT','CONSENSUS','SUM','MAX','SET','RECALCULATED')),
    CONSTRAINT ck_brpfe_public_scope CHECK (public_scope IS NULL OR public_scope IN ('COMPLEX','PARCEL')),
    CONSTRAINT ck_brpfe_quality CHECK (quality IS NULL OR quality IN ('VERIFIED','PNU_FALLBACK','PARTIAL')),
    CONSTRAINT ck_brpfe_conflict CHECK (conflict_status IN ('NONE','SOURCE_CONFLICT','AGGREGATE_CONFLICT','INCOMPLETE')),
    CONSTRAINT ck_brpfe_source_key CHECK (source_record_key ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_brpfe_single_typed CHECK (num_nonnulls(text_value,decimal_value,integer_value,date_value,boolean_value) <= 1)
);

CREATE TABLE public.complex_building_register_profile_summary (
    publication_id uuid NOT NULL REFERENCES public.building_register_profile_publication(publication_id),
    complex_id bigint NOT NULL REFERENCES public.complex(id),
    ratio_scope character varying(16), ratio_quality character varying(24),
    building_coverage_rate numeric(18,8), floor_area_ratio numeric(18,8),
    site_area_m2 numeric(30,12), building_area_m2 numeric(30,12),
    total_floor_area_m2 numeric(30,12), floor_area_ratio_area_m2 numeric(30,12),
    household_scope character varying(16), household_quality character varying(24),
    household_count bigint, family_count bigint, unit_count bigint,
    parking_scope character varying(16), parking_quality character varying(24),
    total_parking_count bigint, parking_per_household numeric(18,8),
    indoor_mechanical_count bigint, indoor_mechanical_area_m2 numeric(30,12),
    outdoor_mechanical_count bigint, outdoor_mechanical_area_m2 numeric(30,12),
    indoor_automatic_count bigint, indoor_automatic_area_m2 numeric(30,12),
    outdoor_automatic_count bigint, outdoor_automatic_area_m2 numeric(30,12),
    building_scope character varying(16), building_quality character varying(24),
    main_building_count bigint, attached_building_count bigint,
    max_ground_floor_count bigint, max_underground_floor_count bigint, max_height_m numeric(30,12),
    structure_names text[], roof_names text[], primary_use_names text[],
    elevator_scope character varying(16), elevator_quality character varying(24),
    ride_elevator_count bigint, emergency_elevator_count bigint,
    safety_scope character varying(16), safety_quality character varying(24),
    seismic_design_status character varying(24), seismic_abilities text[],
    date_scope character varying(16), date_quality character varying(24),
    permit_date date, construction_start_date date, use_approval_date date,
    address_scope character varying(16), address_quality character varying(24),
    parcel_address text, road_address text,
    energy_scope character varying(16), energy_quality character varying(24),
    energy_efficiency_grades text[], energy_saving_rate_min numeric(30,12), energy_saving_rate_max numeric(30,12),
    energy_epi_min numeric(30,12), energy_epi_max numeric(30,12),
    green_building_grades text[], green_cert_score_min numeric(30,12), green_cert_score_max numeric(30,12),
    intelligent_building_grades text[], intelligent_cert_score_min numeric(30,12), intelligent_cert_score_max numeric(30,12),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (publication_id,complex_id),
    CONSTRAINT ck_cbrps_scope CHECK (
        (ratio_scope IS NULL OR ratio_scope IN ('COMPLEX','PARCEL'))
        AND (household_scope IS NULL OR household_scope IN ('COMPLEX','PARCEL'))
        AND (parking_scope IS NULL OR parking_scope IN ('COMPLEX','PARCEL'))
        AND (building_scope IS NULL OR building_scope IN ('COMPLEX','PARCEL'))
        AND (elevator_scope IS NULL OR elevator_scope IN ('COMPLEX','PARCEL'))
        AND (safety_scope IS NULL OR safety_scope IN ('COMPLEX','PARCEL'))
        AND (date_scope IS NULL OR date_scope IN ('COMPLEX','PARCEL'))
        AND (address_scope IS NULL OR address_scope IN ('COMPLEX','PARCEL'))
        AND (energy_scope IS NULL OR energy_scope IN ('COMPLEX','PARCEL'))),
    CONSTRAINT ck_cbrps_quality CHECK (
        (ratio_quality IS NULL OR ratio_quality IN ('VERIFIED','PNU_FALLBACK','PARTIAL'))
        AND (household_quality IS NULL OR household_quality IN ('VERIFIED','PNU_FALLBACK','PARTIAL'))
        AND (parking_quality IS NULL OR parking_quality IN ('VERIFIED','PNU_FALLBACK','PARTIAL'))
        AND (building_quality IS NULL OR building_quality IN ('VERIFIED','PNU_FALLBACK','PARTIAL'))
        AND (elevator_quality IS NULL OR elevator_quality IN ('VERIFIED','PNU_FALLBACK','PARTIAL'))
        AND (safety_quality IS NULL OR safety_quality IN ('VERIFIED','PNU_FALLBACK','PARTIAL'))
        AND (date_quality IS NULL OR date_quality IN ('VERIFIED','PNU_FALLBACK','PARTIAL'))
        AND (address_quality IS NULL OR address_quality IN ('VERIFIED','PNU_FALLBACK','PARTIAL'))
        AND (energy_quality IS NULL OR energy_quality IN ('VERIFIED','PNU_FALLBACK','PARTIAL'))),
    CONSTRAINT ck_cbrps_seismic CHECK (seismic_design_status IS NULL OR seismic_design_status IN ('ALL_APPLIED','PARTIAL','NONE_APPLIED','UNKNOWN'))
);

CREATE INDEX ix_brps_pnu ON public.building_register_profile_site(publication_id,pnu);
CREATE INDEX ix_brpb_pnu ON public.building_register_profile_building(publication_id,pnu);
CREATE INDEX ix_brpfe_lookup ON public.building_register_profile_field_evidence(publication_id,scope,scope_key,field_id);
CREATE INDEX ix_cbrps_ratio ON public.complex_building_register_profile_summary(publication_id,building_coverage_rate,floor_area_ratio);

ALTER TABLE public.complex
    ADD COLUMN family_cnt bigint,
    ADD COLUMN ho_cnt bigint,
    ADD COLUMN main_bld_cnt bigint,
    ADD COLUMN atch_bld_cnt bigint,
    ADD COLUMN tot_pkng_cnt bigint,
    ADD COLUMN max_grnd_flr_cnt bigint,
    ADD COLUMN max_ugrnd_flr_cnt bigint,
    ADD COLUMN max_height_m numeric(30,12),
    ADD COLUMN ride_use_elvt_cnt bigint,
    ADD COLUMN emgen_use_elvt_cnt bigint,
    ADD COLUMN seismic_design_status character varying(24),
    ADD COLUMN permit_date date,
    ADD COLUMN construction_start_date date,
    ADD COLUMN tot_dong_tot_area numeric(30,12),
    ADD COLUMN vl_rat_estm_tot_area numeric(30,12),
    ADD CONSTRAINT ck_complex_profile_seismic CHECK (
        seismic_design_status IS NULL OR seismic_design_status IN ('ALL_APPLIED','PARTIAL','NONE_APPLIED','UNKNOWN'));

ALTER TABLE public.parcel ADD COLUMN road_address text;

CREATE FUNCTION public.publish_building_register_profile(target_publication_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $function$
DECLARE candidate public.building_register_profile_publication%ROWTYPE;
BEGIN
    LOCK TABLE public.building_register_profile_publication IN EXCLUSIVE MODE;
    SELECT * INTO candidate
    FROM public.building_register_profile_publication
    WHERE publication_id=target_publication_id
    FOR UPDATE;
    IF NOT FOUND OR candidate.status <> 'VALIDATED' THEN
        RAISE EXCEPTION 'publication must be VALIDATED';
    END IF;
    IF candidate.site_count <> candidate.expected_site_count
       OR candidate.building_count <> candidate.expected_building_count
       OR candidate.hierarchy_count <> candidate.expected_hierarchy_count
       OR candidate.evidence_count <> candidate.expected_evidence_count
       OR candidate.summary_count <> candidate.expected_summary_count THEN
        RAISE EXCEPTION 'publication row counts are incomplete';
    END IF;
    UPDATE public.building_register_profile_publication
    SET status='SUPERSEDED', superseded_at=now()
    WHERE status='PUBLISHED';
    UPDATE public.building_register_profile_publication
    SET status='PUBLISHED', published_at=now()
    WHERE publication_id=target_publication_id;
END
$function$;

CREATE FUNCTION public.backfill_building_register_profile_operational_columns(target_publication_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $function$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.building_register_profile_publication
        WHERE publication_id=target_publication_id AND status='PUBLISHED') THEN
        RAISE EXCEPTION 'publication must be PUBLISHED';
    END IF;

    UPDATE public.complex complex_row
    SET unit_cnt = CASE WHEN complex_row.unit_cnt IS NULL
                            AND summary.household_quality='VERIFIED'
                            AND summary.household_scope='COMPLEX'
                            AND summary.household_count>0 THEN summary.household_count ELSE complex_row.unit_cnt END,
        family_cnt = CASE WHEN complex_row.family_cnt IS NULL
                              AND summary.household_quality='VERIFIED'
                              AND summary.household_scope='COMPLEX' THEN summary.family_count ELSE complex_row.family_cnt END,
        ho_cnt = CASE WHEN complex_row.ho_cnt IS NULL
                          AND summary.household_quality='VERIFIED'
                          AND summary.household_scope='COMPLEX' THEN summary.unit_count ELSE complex_row.ho_cnt END,
        main_bld_cnt = CASE WHEN complex_row.main_bld_cnt IS NULL
                                AND summary.building_quality='VERIFIED'
                                AND summary.building_scope='COMPLEX' THEN summary.main_building_count ELSE complex_row.main_bld_cnt END,
        atch_bld_cnt = CASE WHEN complex_row.atch_bld_cnt IS NULL
                                AND summary.building_quality='VERIFIED'
                                AND summary.building_scope='COMPLEX' THEN summary.attached_building_count ELSE complex_row.atch_bld_cnt END,
        tot_pkng_cnt = CASE WHEN complex_row.tot_pkng_cnt IS NULL
                                AND summary.parking_quality='VERIFIED'
                                AND summary.parking_scope='COMPLEX' THEN summary.total_parking_count ELSE complex_row.tot_pkng_cnt END,
        max_grnd_flr_cnt = CASE WHEN complex_row.max_grnd_flr_cnt IS NULL
                                    AND summary.building_quality='VERIFIED'
                                    AND summary.building_scope='COMPLEX' THEN summary.max_ground_floor_count ELSE complex_row.max_grnd_flr_cnt END,
        max_ugrnd_flr_cnt = CASE WHEN complex_row.max_ugrnd_flr_cnt IS NULL
                                     AND summary.building_quality='VERIFIED'
                                     AND summary.building_scope='COMPLEX' THEN summary.max_underground_floor_count ELSE complex_row.max_ugrnd_flr_cnt END,
        max_height_m = CASE WHEN complex_row.max_height_m IS NULL
                                AND summary.building_quality='VERIFIED'
                                AND summary.building_scope='COMPLEX' THEN summary.max_height_m ELSE complex_row.max_height_m END,
        ride_use_elvt_cnt = CASE WHEN complex_row.ride_use_elvt_cnt IS NULL
                                     AND summary.elevator_quality='VERIFIED'
                                     AND summary.elevator_scope='COMPLEX' THEN summary.ride_elevator_count ELSE complex_row.ride_use_elvt_cnt END,
        emgen_use_elvt_cnt = CASE WHEN complex_row.emgen_use_elvt_cnt IS NULL
                                      AND summary.elevator_quality='VERIFIED'
                                      AND summary.elevator_scope='COMPLEX' THEN summary.emergency_elevator_count ELSE complex_row.emgen_use_elvt_cnt END,
        seismic_design_status = CASE WHEN complex_row.seismic_design_status IS NULL
                                         AND summary.safety_quality='VERIFIED'
                                         AND summary.safety_scope='COMPLEX' THEN summary.seismic_design_status ELSE complex_row.seismic_design_status END,
        permit_date = CASE WHEN complex_row.permit_date IS NULL
                               AND summary.date_quality='VERIFIED'
                               AND summary.date_scope='COMPLEX' THEN summary.permit_date ELSE complex_row.permit_date END,
        construction_start_date = CASE WHEN complex_row.construction_start_date IS NULL
                                           AND summary.date_quality='VERIFIED'
                                           AND summary.date_scope='COMPLEX' THEN summary.construction_start_date ELSE complex_row.construction_start_date END,
        use_date = CASE WHEN complex_row.use_date IS NULL
                            AND summary.date_quality='VERIFIED'
                            AND summary.date_scope='COMPLEX' THEN summary.use_approval_date ELSE complex_row.use_date END,
        plat_area = CASE WHEN complex_row.plat_area IS NULL
                             AND summary.ratio_quality='VERIFIED'
                             AND summary.ratio_scope='COMPLEX' THEN summary.site_area_m2 ELSE complex_row.plat_area END,
        arch_area = CASE WHEN complex_row.arch_area IS NULL
                             AND summary.ratio_quality='VERIFIED'
                             AND summary.ratio_scope='COMPLEX' THEN summary.building_area_m2 ELSE complex_row.arch_area END,
        tot_area = CASE WHEN complex_row.tot_area IS NULL
                            AND summary.ratio_quality='VERIFIED'
                            AND summary.ratio_scope='COMPLEX' THEN summary.total_floor_area_m2 ELSE complex_row.tot_area END,
        bc_rat = CASE WHEN complex_row.bc_rat IS NULL
                          AND summary.ratio_quality='VERIFIED'
                          AND summary.ratio_scope='COMPLEX' THEN summary.building_coverage_rate ELSE complex_row.bc_rat END,
        vl_rat = CASE WHEN complex_row.vl_rat IS NULL
                          AND summary.ratio_quality='VERIFIED'
                          AND summary.ratio_scope='COMPLEX' THEN summary.floor_area_ratio ELSE complex_row.vl_rat END,
        tot_dong_tot_area = CASE WHEN complex_row.tot_dong_tot_area IS NULL
                                     AND summary.ratio_quality='VERIFIED'
                                     AND summary.ratio_scope='COMPLEX' THEN summary.total_floor_area_m2 ELSE complex_row.tot_dong_tot_area END,
        vl_rat_estm_tot_area = CASE WHEN complex_row.vl_rat_estm_tot_area IS NULL
                                        AND summary.ratio_quality='VERIFIED'
                                        AND summary.ratio_scope='COMPLEX' THEN summary.floor_area_ratio_area_m2 ELSE complex_row.vl_rat_estm_tot_area END
    FROM public.complex_building_register_profile_summary summary
    WHERE summary.publication_id=target_publication_id
      AND summary.complex_id=complex_row.id;

    WITH road_consensus AS (
        SELECT complex_row.parcel_id, min(summary.road_address) AS road_address
        FROM public.complex_building_register_profile_summary summary
        JOIN public.complex complex_row ON complex_row.id=summary.complex_id
        WHERE summary.publication_id=target_publication_id
          AND summary.address_quality IN ('VERIFIED','PNU_FALLBACK')
          AND summary.road_address IS NOT NULL AND btrim(summary.road_address)<>''
        GROUP BY complex_row.parcel_id
        HAVING count(DISTINCT summary.road_address)=1
    )
    UPDATE public.parcel parcel_row
    SET road_address=road_consensus.road_address
    FROM road_consensus
    WHERE parcel_row.id=road_consensus.parcel_id
      AND parcel_row.road_address IS NULL;
END
$function$;
