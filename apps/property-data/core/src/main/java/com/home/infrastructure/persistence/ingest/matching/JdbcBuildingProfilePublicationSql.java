package com.home.infrastructure.persistence.ingest.matching;

final class JdbcBuildingProfilePublicationSql {
    private JdbcBuildingProfilePublicationSql() {}

    static final String SOURCE_COUNTS = """
        WITH source AS (
          SELECT projection.analysis_run_id,projection.parse_run_id,
                 projection.status AS projection_status,analysis.status AS analysis_status,
                 parse.status AS parse_status
          FROM building_register_profile_projection_run projection
          JOIN building_register_profile_analysis_run analysis
            ON analysis.analysis_run_id=projection.analysis_run_id
          JOIN building_register_profile_parse_run parse ON parse.parse_run_id=projection.parse_run_id
          WHERE projection.projection_run_id=:projection
        ), records AS (
          SELECT record.*
          FROM building_register_profile_record record JOIN source ON source.parse_run_id=record.parse_run_id
        ), roots AS (
          SELECT pnu,mgm_bldrgst_pk
          FROM records
          WHERE mgm_bldrgst_pk IS NOT NULL
            AND (endpoint='RECAP_TITLE' OR (endpoint='TITLE' AND regstr_kind_cd='1'))
        ), buildings AS (
          SELECT pnu,mgm_bldrgst_pk
          FROM records
          WHERE endpoint='TITLE' AND regstr_kind_cd IN ('2','3') AND mgm_bldrgst_pk IS NOT NULL
        )
        SELECT source.projection_status='COMPLETED' AND source.analysis_status='COMPLETED'
                   AND source.parse_status='COMPLETED' AS completed,
               (SELECT count(DISTINCT (pnu,mgm_bldrgst_pk))::integer FROM roots) AS site_count,
               (SELECT count(DISTINCT mgm_bldrgst_pk)::integer FROM buildings) AS building_count,
               (SELECT count(*)::integer FROM records) AS hierarchy_count,
               (SELECT count(*)::integer FROM building_register_profile_value value
                JOIN records ON records.id=value.profile_record_id) AS value_count,
               (SELECT count(*)::integer FROM building_register_profile_value value
                JOIN records ON records.id=value.profile_record_id
                WHERE value.field_id NOT IN (:allFields)
                   OR (records.endpoint='BASIC_OVERVIEW' AND value.field_id NOT IN (:basicFields)))
                 AS invalid_field_count,
               (SELECT coalesce(sum(CASE WHEN endpoint='BASIC_OVERVIEW' THEN 5 ELSE 83 END),0)::integer
                FROM records) AS expected_value_count,
               (SELECT count(*)::integer FROM building_register_profile_complex_match match
                WHERE match.analysis_run_id=source.analysis_run_id) AS summary_count,
               (SELECT count(*)::integer FROM (SELECT mgm_bldrgst_pk FROM buildings
                GROUP BY mgm_bldrgst_pk HAVING count(*)>1) duplicate) AS duplicate_building_key_count,
               (SELECT count(*)::integer FROM (SELECT pnu,mgm_bldrgst_pk FROM roots
                GROUP BY pnu,mgm_bldrgst_pk HAVING count(*)>1) duplicate) AS duplicate_root_key_count
        FROM source
        """;

    static final String SITE = """
        INSERT INTO building_register_profile_site(
          publication_id,pnu,root_management_key,bld_nm,plat_plc,new_plat_plc,sigungu_cd,bjdong_cd,
          plat_gb_cd,bun,ji,splot_nm,block,lot,bylot_cnt,road_cd,road_bjdong_cd,
          road_underground_cd,road_main_no,road_sub_no,plat_area,bc_rat,vl_rat,tot_dong_tot_area,
          hhld_cnt,fmly_cnt,main_bld_cnt,atch_bld_cnt,tot_pkng_cnt,pms_day,stcns_day,use_apr_day,
          crtn_day,pmsno_year,pmsno_kik_cd,pmsno_kik_cd_nm,pmsno_gb_cd,pmsno_gb_cd_nm)
        SELECT :publication,record.pnu,record.mgm_bldrgst_pk,
          max(value.text_value) FILTER (WHERE value.field_id='BLD_NM'),
          max(value.text_value) FILTER (WHERE value.field_id='PLAT_PLC'),
          max(value.text_value) FILTER (WHERE value.field_id='NEW_PLAT_PLC'),
          max(value.text_value) FILTER (WHERE value.field_id='SIGUNGU_CD'),
          max(value.text_value) FILTER (WHERE value.field_id='BJDONG_CD'),
          max(value.text_value) FILTER (WHERE value.field_id='PLAT_GB_CD'),
          max(value.text_value) FILTER (WHERE value.field_id='BUN'),
          max(value.text_value) FILTER (WHERE value.field_id='JI'),
          max(value.text_value) FILTER (WHERE value.field_id='SPLOT_NM'),
          max(value.text_value) FILTER (WHERE value.field_id='BLOCK'),
          max(value.text_value) FILTER (WHERE value.field_id='LOT'),
          max(value.integer_value) FILTER (WHERE value.field_id='BYLOT_CNT'),
          max(value.text_value) FILTER (WHERE value.field_id='ROAD_CD'),
          max(value.text_value) FILTER (WHERE value.field_id='ROAD_BJDONG_CD'),
          max(value.text_value) FILTER (WHERE value.field_id='ROAD_UNDERGROUND_CD'),
          max(value.text_value) FILTER (WHERE value.field_id='ROAD_MAIN_NO'),
          max(value.text_value) FILTER (WHERE value.field_id='ROAD_SUB_NO'),
          max(value.decimal_value) FILTER (WHERE value.field_id='PLAT_AREA'),
          max(value.decimal_value) FILTER (WHERE value.field_id='BC_RAT'),
          max(value.decimal_value) FILTER (WHERE value.field_id='VL_RAT'),
          max(value.decimal_value) FILTER (WHERE value.field_id='TOT_DONG_TOT_AREA'),
          max(value.integer_value) FILTER (WHERE value.field_id='HHLD_CNT'),
          max(value.integer_value) FILTER (WHERE value.field_id='FMLY_CNT'),
          max(value.integer_value) FILTER (WHERE value.field_id='MAIN_BLD_CNT'),
          max(value.integer_value) FILTER (WHERE value.field_id='ATCH_BLD_CNT'),
          max(value.integer_value) FILTER (WHERE value.field_id='TOT_PKNG_CNT'),
          max(value.date_value) FILTER (WHERE value.field_id='PMS_DAY'),
          max(value.date_value) FILTER (WHERE value.field_id='STCNS_DAY'),
          max(value.date_value) FILTER (WHERE value.field_id='USE_APR_DAY'),
          max(value.date_value) FILTER (WHERE value.field_id='CRTN_DAY'),
          max(value.text_value) FILTER (WHERE value.field_id='PMSNO_YEAR'),
          max(value.text_value) FILTER (WHERE value.field_id='PMSNO_KIK_CD'),
          max(value.text_value) FILTER (WHERE value.field_id='PMSNO_KIK_CD_NM'),
          max(value.text_value) FILTER (WHERE value.field_id='PMSNO_GB_CD'),
          max(value.text_value) FILTER (WHERE value.field_id='PMSNO_GB_CD_NM')
        FROM building_register_profile_projection_run projection
        JOIN building_register_profile_record record ON record.parse_run_id=projection.parse_run_id
        LEFT JOIN building_register_profile_value value ON value.profile_record_id=record.id
          AND value.value_state IN ('ZERO','POSITIVE','VALID') AND value.field_scope='SITE'
        WHERE projection.projection_run_id=:projection AND record.mgm_bldrgst_pk IS NOT NULL
          AND (record.endpoint='RECAP_TITLE' OR (record.endpoint='TITLE' AND record.regstr_kind_cd='1'))
        GROUP BY record.pnu,record.mgm_bldrgst_pk
        ON CONFLICT DO NOTHING
        """;

    static final String BUILDING = """
        INSERT INTO building_register_profile_building(
          publication_id,pnu,management_key,parent_management_key,main_atch_gb_cd,main_atch_gb_cd_nm,
          dong_nm,arch_area,tot_area,vl_rat_estm_tot_area,atch_bld_area,ho_cnt,indr_mech_utcnt,
          indr_mech_area,oudr_mech_utcnt,oudr_mech_area,indr_auto_utcnt,indr_auto_area,
          oudr_auto_utcnt,oudr_auto_area,heit,grnd_flr_cnt,ugrnd_flr_cnt,ride_use_elvt_cnt,
          emgen_use_elvt_cnt,strct_cd,strct_cd_nm,etc_strct,roof_cd,roof_cd_nm,etc_roof,
          main_purps_cd,main_purps_cd_nm,etc_purps,rserthqk_dsgn_apply_yn,rserthqk_ability,
          engr_grade,engr_rat,engr_epi,gn_bld_grade,gn_bld_cert,itg_bld_grade,itg_bld_cert)
        SELECT :publication,record.pnu,record.mgm_bldrgst_pk,record.mgm_up_bldrgst_pk,
          max(value.text_value) FILTER (WHERE value.field_id='MAIN_ATCH_GB_CD'),
          max(value.text_value) FILTER (WHERE value.field_id='MAIN_ATCH_GB_CD_NM'),
          max(value.text_value) FILTER (WHERE value.field_id='DONG_NM'),
          max(value.decimal_value) FILTER (WHERE value.field_id='ARCH_AREA'),
          max(value.decimal_value) FILTER (WHERE value.field_id='TOT_AREA'),
          max(value.decimal_value) FILTER (WHERE value.field_id='VL_RAT_ESTM_TOT_AREA'),
          max(value.decimal_value) FILTER (WHERE value.field_id='ATCH_BLD_AREA'),
          max(value.integer_value) FILTER (WHERE value.field_id='HO_CNT'),
          max(value.integer_value) FILTER (WHERE value.field_id='INDR_MECH_UTCNT'),
          max(value.decimal_value) FILTER (WHERE value.field_id='INDR_MECH_AREA'),
          max(value.integer_value) FILTER (WHERE value.field_id='OUDR_MECH_UTCNT'),
          max(value.decimal_value) FILTER (WHERE value.field_id='OUDR_MECH_AREA'),
          max(value.integer_value) FILTER (WHERE value.field_id='INDR_AUTO_UTCNT'),
          max(value.decimal_value) FILTER (WHERE value.field_id='INDR_AUTO_AREA'),
          max(value.integer_value) FILTER (WHERE value.field_id='OUDR_AUTO_UTCNT'),
          max(value.decimal_value) FILTER (WHERE value.field_id='OUDR_AUTO_AREA'),
          max(value.decimal_value) FILTER (WHERE value.field_id='HEIT'),
          max(value.integer_value) FILTER (WHERE value.field_id='GRND_FLR_CNT'),
          max(value.integer_value) FILTER (WHERE value.field_id='UGRND_FLR_CNT'),
          max(value.integer_value) FILTER (WHERE value.field_id='RIDE_USE_ELVT_CNT'),
          max(value.integer_value) FILTER (WHERE value.field_id='EMGEN_USE_ELVT_CNT'),
          max(value.text_value) FILTER (WHERE value.field_id='STRCT_CD'),
          max(value.text_value) FILTER (WHERE value.field_id='STRCT_CD_NM'),
          max(value.text_value) FILTER (WHERE value.field_id='ETC_STRCT'),
          max(value.text_value) FILTER (WHERE value.field_id='ROOF_CD'),
          max(value.text_value) FILTER (WHERE value.field_id='ROOF_CD_NM'),
          max(value.text_value) FILTER (WHERE value.field_id='ETC_ROOF'),
          max(value.text_value) FILTER (WHERE value.field_id='MAIN_PURPS_CD'),
          max(value.text_value) FILTER (WHERE value.field_id='MAIN_PURPS_CD_NM'),
          max(value.text_value) FILTER (WHERE value.field_id='ETC_PURPS'),
          bool_or(value.boolean_value) FILTER (WHERE value.field_id='RSERTHQK_DSGN_APPLY_YN'),
          max(value.text_value) FILTER (WHERE value.field_id='RSERTHQK_ABILITY'),
          max(value.text_value) FILTER (WHERE value.field_id='ENGR_GRADE'),
          max(value.decimal_value) FILTER (WHERE value.field_id='ENGR_RAT'),
          max(value.decimal_value) FILTER (WHERE value.field_id='ENGR_EPI'),
          max(value.text_value) FILTER (WHERE value.field_id='GN_BLD_GRADE'),
          max(value.decimal_value) FILTER (WHERE value.field_id='GN_BLD_CERT'),
          max(value.text_value) FILTER (WHERE value.field_id='ITG_BLD_GRADE'),
          max(value.decimal_value) FILTER (WHERE value.field_id='ITG_BLD_CERT')
        FROM building_register_profile_projection_run projection
        JOIN building_register_profile_record record ON record.parse_run_id=projection.parse_run_id
        LEFT JOIN building_register_profile_value value ON value.profile_record_id=record.id
          AND value.value_state IN ('ZERO','POSITIVE','VALID') AND value.field_scope='BUILDING'
        WHERE projection.projection_run_id=:projection AND record.endpoint='TITLE'
          AND record.regstr_kind_cd IN ('2','3') AND record.mgm_bldrgst_pk IS NOT NULL
        GROUP BY record.pnu,record.mgm_bldrgst_pk,record.mgm_up_bldrgst_pk
        ON CONFLICT DO NOTHING
        """;

    static final String HIERARCHY = """
        INSERT INTO building_register_profile_hierarchy(
          publication_id,pnu,source_record_key,mgm_bldrgst_pk,mgm_up_bldrgst_pk,regstr_gb_cd,
          regstr_gb_cd_nm,regstr_kind_cd,regstr_kind_cd_nm,new_old_regstr_gb_cd,
          new_old_regstr_gb_cd_nm,rnum)
        SELECT :publication,record.pnu,
          md5(concat_ws('|',record.parse_run_id::text,record.raw_page_id::text,
            record.item_index::text,coalesce(record.content_sha256,''))) ||
          md5(concat_ws('|','profile',record.parse_run_id::text,record.raw_page_id::text,
            record.item_index::text,coalesce(record.content_sha256,''))),
          record.mgm_bldrgst_pk,record.mgm_up_bldrgst_pk,
          max(value.text_value) FILTER (WHERE value.field_id='REGSTR_GB_CD'),
          max(value.text_value) FILTER (WHERE value.field_id='REGSTR_GB_CD_NM'),
          coalesce(record.regstr_kind_cd,max(value.text_value) FILTER (WHERE value.field_id='REGSTR_KIND_CD')),
          max(value.text_value) FILTER (WHERE value.field_id='REGSTR_KIND_CD_NM'),
          max(value.text_value) FILTER (WHERE value.field_id='NEW_OLD_REGSTR_GB_CD'),
          max(value.text_value) FILTER (WHERE value.field_id='NEW_OLD_REGSTR_GB_CD_NM'),
          max(value.integer_value) FILTER (WHERE value.field_id='RNUM')
        FROM building_register_profile_projection_run projection
        JOIN building_register_profile_record record ON record.parse_run_id=projection.parse_run_id
        LEFT JOIN building_register_profile_value value ON value.profile_record_id=record.id
          AND value.value_state IN ('ZERO','POSITIVE','VALID') AND value.field_scope='HIERARCHY'
        WHERE projection.projection_run_id=:projection
        GROUP BY record.id
        ON CONFLICT DO NOTHING
        """;

    static final String EVIDENCE = """
        INSERT INTO building_register_profile_field_evidence(
          evidence_id,publication_id,scope,scope_key,field_id,value_state,raw_value,text_value,
          decimal_value,integer_value,date_value,boolean_value,source_method,aggregation_method,
          public_scope,quality,conflict_status,source_record_key)
        SELECT gen_random_uuid(),:publication,value.field_scope,
          CASE value.field_scope
            WHEN 'SITE' THEN coalesce(assignment.root_management_key,record.mgm_bldrgst_pk,source.source_record_key)
            WHEN 'BUILDING' THEN coalesce(record.mgm_bldrgst_pk,source.source_record_key)
            ELSE source.source_record_key END,
          value.field_id,value.value_state,value.raw_value,value.text_value,value.decimal_value,
          value.integer_value,value.date_value,value.boolean_value,
          CASE value.field_scope WHEN 'SITE' THEN 'PNU_ROOT' ELSE 'PROVIDER_DIRECT' END,
          value.aggregation_method,NULL,NULL,'NONE',source.source_record_key
        FROM building_register_profile_projection_run projection
        JOIN building_register_profile_record record ON record.parse_run_id=projection.parse_run_id
        JOIN building_register_profile_value value ON value.profile_record_id=record.id
        LEFT JOIN building_register_profile_scope_assignment assignment
          ON assignment.analysis_run_id=projection.analysis_run_id AND assignment.profile_record_id=record.id
        CROSS JOIN LATERAL (SELECT
          md5(concat_ws('|',record.parse_run_id::text,record.raw_page_id::text,
            record.item_index::text,coalesce(record.content_sha256,''))) ||
          md5(concat_ws('|','profile',record.parse_run_id::text,record.raw_page_id::text,
            record.item_index::text,coalesce(record.content_sha256,''))) AS source_record_key) source
        WHERE projection.projection_run_id=:projection
        ON CONFLICT DO NOTHING
        """;

    static final String EVIDENCE_CLASSIFICATION = """
        WITH root_evidence AS (
          SELECT evidence.evidence_id,hierarchy.pnu,evidence.field_id,evidence.value_state,
                 evidence.text_value,evidence.decimal_value,evidence.integer_value,
                 evidence.date_value,evidence.boolean_value
          FROM building_register_profile_field_evidence evidence
          JOIN building_register_profile_hierarchy hierarchy
            ON hierarchy.publication_id=evidence.publication_id
           AND hierarchy.source_record_key=evidence.source_record_key
          JOIN building_register_profile_site site
            ON site.publication_id=hierarchy.publication_id AND site.pnu=hierarchy.pnu
           AND site.root_management_key=hierarchy.mgm_bldrgst_pk
          WHERE evidence.publication_id=:publication AND evidence.scope='SITE'
        ), classification AS (
          SELECT pnu,field_id,
            CASE
              WHEN NOT bool_and(value_state IN ('ZERO','POSITIVE','VALID')) THEN 'INCOMPLETE'
              WHEN field_id IN ('BC_RAT','VL_RAT')
                   AND max(decimal_value)-min(decimal_value)>0.01 THEN 'SOURCE_CONFLICT'
              WHEN field_id IN ('PLAT_AREA','TOT_DONG_TOT_AREA')
                   AND max(decimal_value)-min(decimal_value)>0.001 THEN 'SOURCE_CONFLICT'
              WHEN count(DISTINCT jsonb_build_array(
                     text_value,decimal_value,integer_value,date_value,boolean_value))>1
                   THEN 'SOURCE_CONFLICT'
              ELSE 'NONE'
            END AS conflict_status
          FROM root_evidence GROUP BY pnu,field_id
        )
        UPDATE building_register_profile_field_evidence evidence
        SET conflict_status=classification.conflict_status
        FROM building_register_profile_hierarchy hierarchy,classification
        WHERE evidence.publication_id=:publication AND evidence.scope='SITE'
          AND hierarchy.publication_id=evidence.publication_id
          AND hierarchy.source_record_key=evidence.source_record_key
          AND classification.pnu=hierarchy.pnu AND classification.field_id=evidence.field_id
          AND EXISTS (SELECT 1 FROM building_register_profile_site site
                      WHERE site.publication_id=hierarchy.publication_id AND site.pnu=hierarchy.pnu
                        AND site.root_management_key=hierarchy.mgm_bldrgst_pk)
        """;

    static final String SUMMARY = """
        WITH building AS (
          SELECT source.complex_id,count(*) AS expected_count,
            CASE WHEN count(profile.ho_cnt)=count(*) THEN sum(profile.ho_cnt) END AS ho_cnt,
            CASE WHEN count(profile.arch_area) FILTER (WHERE profile.arch_area>0)=count(*)
                 THEN sum(profile.arch_area) FILTER (WHERE profile.arch_area>0) END AS arch_area,
            CASE WHEN count(profile.tot_area) FILTER (WHERE profile.tot_area>0)=count(*)
                 THEN sum(profile.tot_area) FILTER (WHERE profile.tot_area>0) END AS tot_area,
            CASE WHEN count(profile.vl_rat_estm_tot_area) FILTER (WHERE profile.vl_rat_estm_tot_area>0)=count(*)
                 THEN sum(profile.vl_rat_estm_tot_area) FILTER (WHERE profile.vl_rat_estm_tot_area>0) END AS vl_area,
            CASE WHEN count(profile.indr_mech_utcnt)=count(*) THEN sum(profile.indr_mech_utcnt) END AS indr_mech,
            CASE WHEN count(profile.indr_mech_area)=count(*) THEN sum(profile.indr_mech_area) END AS indr_mech_area,
            CASE WHEN count(profile.oudr_mech_utcnt)=count(*) THEN sum(profile.oudr_mech_utcnt) END AS oudr_mech,
            CASE WHEN count(profile.oudr_mech_area)=count(*) THEN sum(profile.oudr_mech_area) END AS oudr_mech_area,
            CASE WHEN count(profile.indr_auto_utcnt)=count(*) THEN sum(profile.indr_auto_utcnt) END AS indr_auto,
            CASE WHEN count(profile.indr_auto_area)=count(*) THEN sum(profile.indr_auto_area) END AS indr_auto_area,
            CASE WHEN count(profile.oudr_auto_utcnt)=count(*) THEN sum(profile.oudr_auto_utcnt) END AS oudr_auto,
            CASE WHEN count(profile.oudr_auto_area)=count(*) THEN sum(profile.oudr_auto_area) END AS oudr_auto_area,
            max(profile.grnd_flr_cnt) FILTER (WHERE profile.grnd_flr_cnt>0) AS max_ground,
            count(profile.grnd_flr_cnt) FILTER (WHERE profile.grnd_flr_cnt>0) AS ground_count,
            max(profile.ugrnd_flr_cnt) AS max_underground,count(profile.ugrnd_flr_cnt) AS underground_count,
            max(profile.heit) FILTER (WHERE profile.heit>0) AS max_height,
            count(profile.heit) FILTER (WHERE profile.heit>0) AS height_count,
            CASE WHEN count(profile.ride_use_elvt_cnt)=count(*) THEN sum(profile.ride_use_elvt_cnt) END AS ride_elevator,
            CASE WHEN count(profile.emgen_use_elvt_cnt)=count(*) THEN sum(profile.emgen_use_elvt_cnt) END AS emergency_elevator,
            array_agg(DISTINCT profile.strct_cd_nm) FILTER (WHERE profile.strct_cd_nm IS NOT NULL AND btrim(profile.strct_cd_nm)<>'') AS structures,
            array_agg(DISTINCT profile.roof_cd_nm) FILTER (WHERE profile.roof_cd_nm IS NOT NULL AND btrim(profile.roof_cd_nm)<>'') AS roofs,
            array_agg(DISTINCT profile.main_purps_cd_nm) FILTER (WHERE profile.main_purps_cd_nm IS NOT NULL AND btrim(profile.main_purps_cd_nm)<>'') AS uses,
            count(profile.rserthqk_dsgn_apply_yn) AS seismic_count,
            CASE WHEN count(profile.rserthqk_dsgn_apply_yn)=0 THEN 'UNKNOWN'
                 WHEN count(profile.rserthqk_dsgn_apply_yn)<count(*) THEN 'PARTIAL'
                 WHEN bool_and(profile.rserthqk_dsgn_apply_yn) THEN 'ALL_APPLIED'
                 WHEN bool_or(profile.rserthqk_dsgn_apply_yn) THEN 'PARTIAL'
                 ELSE 'NONE_APPLIED' END AS seismic_status,
            array_agg(DISTINCT profile.rserthqk_ability) FILTER (WHERE profile.rserthqk_ability IS NOT NULL AND btrim(profile.rserthqk_ability)<>'') AS seismic_abilities,
            array_agg(DISTINCT profile.engr_grade) FILTER (WHERE profile.engr_grade IS NOT NULL AND btrim(profile.engr_grade)<>'') AS energy_grades,
            min(profile.engr_rat) FILTER (WHERE profile.engr_rat>0) AS energy_rate_min,
            max(profile.engr_rat) FILTER (WHERE profile.engr_rat>0) AS energy_rate_max,
            min(profile.engr_epi) FILTER (WHERE profile.engr_epi>0) AS energy_epi_min,
            max(profile.engr_epi) FILTER (WHERE profile.engr_epi>0) AS energy_epi_max,
            array_agg(DISTINCT profile.gn_bld_grade) FILTER (WHERE profile.gn_bld_grade IS NOT NULL AND btrim(profile.gn_bld_grade)<>'') AS green_grades,
            min(profile.gn_bld_cert) FILTER (WHERE profile.gn_bld_cert>0) AS green_min,
            max(profile.gn_bld_cert) FILTER (WHERE profile.gn_bld_cert>0) AS green_max,
            array_agg(DISTINCT profile.itg_bld_grade) FILTER (WHERE profile.itg_bld_grade IS NOT NULL AND btrim(profile.itg_bld_grade)<>'') AS intelligent_grades,
            min(profile.itg_bld_cert) FILTER (WHERE profile.itg_bld_cert>0) AS intelligent_min,
            max(profile.itg_bld_cert) FILTER (WHERE profile.itg_bld_cert>0) AS intelligent_max
          FROM complex_building_register_building source
          LEFT JOIN building_register_profile_building profile
            ON profile.publication_id=:publication AND profile.management_key=source.source_management_key
          WHERE source.projection_run_id=:projection GROUP BY source.complex_id
        ), pnu_site AS (
          SELECT pnu,count(*) AS root_count,
            CASE WHEN count(bc_rat) FILTER (WHERE bc_rat>0)=count(*)
                      AND max(bc_rat)-min(bc_rat)<=0.01 THEN min(bc_rat) END AS bc_rat,
            CASE WHEN count(vl_rat) FILTER (WHERE vl_rat>0)=count(*)
                      AND max(vl_rat)-min(vl_rat)<=0.01 THEN min(vl_rat) END AS vl_rat,
            CASE WHEN count(plat_area) FILTER (WHERE plat_area>0)=count(*)
                      AND max(plat_area)-min(plat_area)<=0.001 THEN min(plat_area) END AS plat_area,
            CASE WHEN count(tot_dong_tot_area) FILTER (WHERE tot_dong_tot_area>0)=count(*)
                      AND max(tot_dong_tot_area)-min(tot_dong_tot_area)<=0.001
                 THEN min(tot_dong_tot_area) END AS tot_dong_tot_area,
            CASE WHEN count(hhld_cnt)=count(*) AND min(hhld_cnt)=max(hhld_cnt) THEN min(hhld_cnt) END AS hhld_cnt,
            CASE WHEN count(fmly_cnt)=count(*) AND min(fmly_cnt)=max(fmly_cnt) THEN min(fmly_cnt) END AS fmly_cnt,
            CASE WHEN count(main_bld_cnt)=count(*) AND min(main_bld_cnt)=max(main_bld_cnt) THEN min(main_bld_cnt) END AS main_bld_cnt,
            CASE WHEN count(atch_bld_cnt)=count(*) AND min(atch_bld_cnt)=max(atch_bld_cnt) THEN min(atch_bld_cnt) END AS atch_bld_cnt,
            CASE WHEN count(tot_pkng_cnt)=count(*) AND min(tot_pkng_cnt)=max(tot_pkng_cnt) THEN min(tot_pkng_cnt) END AS tot_pkng_cnt,
            CASE WHEN count(pms_day)=count(*) AND min(pms_day)=max(pms_day) THEN min(pms_day) END AS pms_day,
            CASE WHEN count(stcns_day)=count(*) AND min(stcns_day)=max(stcns_day) THEN min(stcns_day) END AS stcns_day,
            CASE WHEN count(use_apr_day)=count(*) AND min(use_apr_day)=max(use_apr_day) THEN min(use_apr_day) END AS use_apr_day,
            CASE WHEN count(plat_plc)=count(*) AND min(plat_plc)=max(plat_plc) THEN min(plat_plc) END AS plat_plc,
            CASE WHEN count(new_plat_plc)=count(*) AND min(new_plat_plc)=max(new_plat_plc) THEN min(new_plat_plc) END AS new_plat_plc
          FROM building_register_profile_site WHERE publication_id=:publication GROUP BY pnu
        ), source AS (
          SELECT projected.complex_id,match.pnu,
                 coalesce(nullif(direct_site.bc_rat,0),pnu_site.bc_rat) AS bc_rat,
                 coalesce(nullif(direct_site.vl_rat,0),pnu_site.vl_rat) AS vl_rat,
                 coalesce(nullif(direct_site.plat_area,0),pnu_site.plat_area) AS plat_area,
                 coalesce(nullif(direct_site.tot_dong_tot_area,0),pnu_site.tot_dong_tot_area)
                   AS tot_dong_tot_area,
                 coalesce(direct_site.hhld_cnt,pnu_site.hhld_cnt) AS hhld_cnt,
                 coalesce(direct_site.fmly_cnt,pnu_site.fmly_cnt) AS fmly_cnt,
                 coalesce(direct_site.main_bld_cnt,pnu_site.main_bld_cnt) AS main_bld_cnt,
                 coalesce(direct_site.atch_bld_cnt,pnu_site.atch_bld_cnt) AS atch_bld_cnt,
                 coalesce(direct_site.tot_pkng_cnt,pnu_site.tot_pkng_cnt) AS tot_pkng_cnt,
                 coalesce(direct_site.pms_day,pnu_site.pms_day) AS pms_day,
                 coalesce(direct_site.stcns_day,pnu_site.stcns_day) AS stcns_day,
                 coalesce(direct_site.use_apr_day,pnu_site.use_apr_day) AS use_apr_day,
                 coalesce(direct_site.plat_plc,pnu_site.plat_plc) AS plat_plc,
                 coalesce(direct_site.new_plat_plc,pnu_site.new_plat_plc) AS new_plat_plc,
                 (nullif(direct_site.bc_rat,0) IS NULL AND pnu_site.bc_rat IS NOT NULL
                   OR nullif(direct_site.vl_rat,0) IS NULL AND pnu_site.vl_rat IS NOT NULL
                   OR nullif(direct_site.plat_area,0) IS NULL AND pnu_site.plat_area IS NOT NULL
                   OR nullif(direct_site.tot_dong_tot_area,0) IS NULL
                      AND pnu_site.tot_dong_tot_area IS NOT NULL) AS ratio_fallback,
                 (direct_site.hhld_cnt IS NULL AND pnu_site.hhld_cnt IS NOT NULL
                   OR direct_site.fmly_cnt IS NULL AND pnu_site.fmly_cnt IS NOT NULL) AS household_fallback,
                 (direct_site.tot_pkng_cnt IS NULL AND pnu_site.tot_pkng_cnt IS NOT NULL) AS parking_fallback,
                 (direct_site.main_bld_cnt IS NULL AND pnu_site.main_bld_cnt IS NOT NULL
                   OR direct_site.atch_bld_cnt IS NULL AND pnu_site.atch_bld_cnt IS NOT NULL) AS building_fallback,
                 (direct_site.pms_day IS NULL AND pnu_site.pms_day IS NOT NULL
                   OR direct_site.stcns_day IS NULL AND pnu_site.stcns_day IS NOT NULL
                   OR direct_site.use_apr_day IS NULL AND pnu_site.use_apr_day IS NOT NULL) AS date_fallback,
                 (direct_site.plat_plc IS NULL AND pnu_site.plat_plc IS NOT NULL
                   OR direct_site.new_plat_plc IS NULL AND pnu_site.new_plat_plc IS NOT NULL) AS address_fallback,
                 building.ho_cnt,building.arch_area AS sum_arch_area,building.tot_area AS sum_tot_area,
                 building.vl_area,building.indr_mech,building.indr_mech_area,building.oudr_mech,
                 building.oudr_mech_area,building.indr_auto,building.indr_auto_area,building.oudr_auto,
                 building.oudr_auto_area,building.expected_count,building.max_ground,building.ground_count,
                 building.max_underground,building.underground_count,building.max_height,building.height_count,
                 building.ride_elevator,building.emergency_elevator,building.structures,building.roofs,
                 building.uses,building.seismic_count,building.seismic_status,building.seismic_abilities,
                 building.energy_grades,
                 building.energy_rate_min,building.energy_rate_max,building.energy_epi_min,building.energy_epi_max,
                 building.green_grades,building.green_min,building.green_max,building.intelligent_grades,
                 building.intelligent_min,building.intelligent_max
          FROM complex_building_register_profile projected
          JOIN building_register_profile_complex_match match
            ON match.analysis_run_id=projected.analysis_run_id AND match.complex_id=projected.complex_id
          LEFT JOIN building_register_profile_site direct_site ON direct_site.publication_id=:publication
            AND projected.projectable AND direct_site.pnu=match.pnu
            AND direct_site.root_management_key=projected.source_root_management_key
          LEFT JOIN pnu_site ON pnu_site.pnu=match.pnu
          LEFT JOIN building ON building.complex_id=projected.complex_id
          WHERE projected.projection_run_id=:projection
        )
        INSERT INTO complex_building_register_profile_summary(
          publication_id,complex_id,ratio_scope,ratio_quality,building_coverage_rate,floor_area_ratio,
          site_area_m2,building_area_m2,total_floor_area_m2,floor_area_ratio_area_m2,
          household_scope,household_quality,household_count,family_count,unit_count,
          parking_scope,parking_quality,total_parking_count,parking_per_household,
          indoor_mechanical_count,indoor_mechanical_area_m2,outdoor_mechanical_count,outdoor_mechanical_area_m2,
          indoor_automatic_count,indoor_automatic_area_m2,outdoor_automatic_count,outdoor_automatic_area_m2,
          building_scope,building_quality,main_building_count,attached_building_count,max_ground_floor_count,
          max_underground_floor_count,max_height_m,structure_names,roof_names,primary_use_names,
          elevator_scope,elevator_quality,ride_elevator_count,emergency_elevator_count,
          safety_scope,safety_quality,seismic_design_status,seismic_abilities,date_scope,date_quality,
          permit_date,construction_start_date,use_approval_date,address_scope,address_quality,
          parcel_address,road_address,energy_scope,energy_quality,energy_efficiency_grades,
          energy_saving_rate_min,energy_saving_rate_max,energy_epi_min,energy_epi_max,green_building_grades,
          green_cert_score_min,green_cert_score_max,intelligent_building_grades,
          intelligent_cert_score_min,intelligent_cert_score_max)
        SELECT :publication,complex_id,
          CASE WHEN num_nonnulls(bc_rat,vl_rat,plat_area,sum_arch_area,
                                  coalesce(tot_dong_tot_area,sum_tot_area),vl_area)>0
                 THEN CASE WHEN ratio_fallback THEN 'PARCEL' ELSE 'COMPLEX' END END,
          CASE WHEN num_nonnulls(bc_rat,vl_rat,plat_area,sum_arch_area,
                                  coalesce(tot_dong_tot_area,sum_tot_area),vl_area)>0
                 THEN CASE WHEN ratio_fallback THEN 'PNU_FALLBACK' ELSE 'VERIFIED' END END,
          bc_rat,vl_rat,plat_area,sum_arch_area,coalesce(tot_dong_tot_area,sum_tot_area),vl_area,
          CASE WHEN num_nonnulls(hhld_cnt,fmly_cnt,ho_cnt)>0
                 THEN CASE WHEN household_fallback THEN 'PARCEL' ELSE 'COMPLEX' END END,
          CASE WHEN num_nonnulls(hhld_cnt,fmly_cnt,ho_cnt)>0
                 THEN CASE WHEN household_fallback THEN 'PNU_FALLBACK' ELSE 'VERIFIED' END END,
          hhld_cnt,fmly_cnt,ho_cnt,
          CASE WHEN num_nonnulls(tot_pkng_cnt,indr_mech,oudr_mech,indr_auto,oudr_auto)>0
                 THEN CASE WHEN num_nonnulls(indr_mech,oudr_mech,indr_auto,oudr_auto)=4
                                   AND tot_pkng_cnt IS NOT NULL
                                   AND tot_pkng_cnt<>indr_mech+oudr_mech+indr_auto+oudr_auto THEN 'COMPLEX'
                                WHEN parking_fallback THEN 'PARCEL' ELSE 'COMPLEX' END END,
          CASE WHEN num_nonnulls(tot_pkng_cnt,indr_mech,oudr_mech,indr_auto,oudr_auto)>0
                 THEN CASE WHEN num_nonnulls(indr_mech,oudr_mech,indr_auto,oudr_auto)=4
                                   AND tot_pkng_cnt IS NOT NULL
                                   AND tot_pkng_cnt<>indr_mech+oudr_mech+indr_auto+oudr_auto THEN 'PARTIAL'
                                WHEN parking_fallback THEN 'PNU_FALLBACK' ELSE 'VERIFIED' END END,
          CASE WHEN num_nonnulls(indr_mech,oudr_mech,indr_auto,oudr_auto)=4 THEN
                 CASE WHEN tot_pkng_cnt IS NULL THEN indr_mech+oudr_mech+indr_auto+oudr_auto
                      WHEN tot_pkng_cnt<>indr_mech+oudr_mech+indr_auto+oudr_auto THEN NULL
                      ELSE tot_pkng_cnt END
               ELSE tot_pkng_cnt END,
          CASE WHEN hhld_cnt>0 THEN
                 CASE WHEN num_nonnulls(indr_mech,oudr_mech,indr_auto,oudr_auto)=4 THEN
                        CASE WHEN tot_pkng_cnt IS NULL THEN
                               (indr_mech+oudr_mech+indr_auto+oudr_auto)::numeric/hhld_cnt
                             WHEN tot_pkng_cnt=indr_mech+oudr_mech+indr_auto+oudr_auto THEN
                               tot_pkng_cnt::numeric/hhld_cnt END
                      WHEN tot_pkng_cnt IS NOT NULL THEN tot_pkng_cnt::numeric/hhld_cnt END
               END,
          indr_mech,indr_mech_area,oudr_mech,oudr_mech_area,indr_auto,indr_auto_area,oudr_auto,oudr_auto_area,
          CASE WHEN num_nonnulls(main_bld_cnt,atch_bld_cnt,max_ground,max_underground,max_height,
                    structures,roofs,uses)>0 THEN CASE WHEN building_fallback THEN 'PARCEL' ELSE 'COMPLEX' END END,
          CASE WHEN (max_ground IS NOT NULL AND ground_count<expected_count)
                      OR (max_underground IS NOT NULL AND underground_count<expected_count)
                      OR (max_height IS NOT NULL AND height_count<expected_count) THEN 'PARTIAL'
               WHEN num_nonnulls(main_bld_cnt,atch_bld_cnt,max_ground,max_underground,max_height,
                      structures,roofs,uses)>0
                 THEN CASE WHEN building_fallback THEN 'PNU_FALLBACK' ELSE 'VERIFIED' END END,
          main_bld_cnt,atch_bld_cnt,max_ground,max_underground,max_height,structures,roofs,uses,
          CASE WHEN num_nonnulls(ride_elevator,emergency_elevator)>0 THEN 'COMPLEX' END,
          CASE WHEN num_nonnulls(ride_elevator,emergency_elevator)>0 THEN 'VERIFIED' END,
          ride_elevator,emergency_elevator,
          CASE WHEN expected_count>0 THEN 'COMPLEX' END,
          CASE WHEN expected_count>0 THEN
                 CASE WHEN seismic_count<expected_count THEN 'PARTIAL' ELSE 'VERIFIED' END END,
          seismic_status,seismic_abilities,
          CASE WHEN num_nonnulls(pms_day,stcns_day,use_apr_day)>0
                 THEN CASE WHEN date_fallback THEN 'PARCEL' ELSE 'COMPLEX' END END,
          CASE WHEN num_nonnulls(pms_day,stcns_day,use_apr_day)>0
                 THEN CASE WHEN date_fallback THEN 'PNU_FALLBACK' ELSE 'VERIFIED' END END,
          pms_day,stcns_day,use_apr_day,
          CASE WHEN num_nonnulls(plat_plc,new_plat_plc)>0
                 THEN CASE WHEN address_fallback THEN 'PARCEL' ELSE 'COMPLEX' END END,
          CASE WHEN num_nonnulls(plat_plc,new_plat_plc)>0
                 THEN CASE WHEN address_fallback THEN 'PNU_FALLBACK' ELSE 'VERIFIED' END END,
          plat_plc,new_plat_plc,
          CASE WHEN num_nonnulls(energy_grades,energy_rate_min,energy_rate_max,energy_epi_min,energy_epi_max,
                    green_grades,green_min,green_max,intelligent_grades,intelligent_min,intelligent_max)>0 THEN 'COMPLEX' END,
          CASE WHEN num_nonnulls(energy_grades,energy_rate_min,energy_rate_max,energy_epi_min,energy_epi_max,
                    green_grades,green_min,green_max,intelligent_grades,intelligent_min,intelligent_max)>0 THEN 'PARTIAL' END,
          energy_grades,energy_rate_min,energy_rate_max,energy_epi_min,energy_epi_max,green_grades,
          green_min,green_max,intelligent_grades,intelligent_min,intelligent_max
        FROM source
        ON CONFLICT DO NOTHING
        """;

    static final String CONTENT_KEYS = """
        SELECT jsonb_build_array(
                 source_record_key,scope,scope_key,field_id,value_state,raw_value,text_value,
                 decimal_value,integer_value,date_value,boolean_value,source_method,
                 aggregation_method,public_scope,quality,conflict_status)::text
        FROM building_register_profile_field_evidence
        WHERE publication_id=?
        ORDER BY scope,scope_key,field_id,source_record_key
        """;

    static final String AGGREGATE_CONFLICTS = """
        UPDATE building_register_profile_field_evidence evidence
        SET conflict_status='AGGREGATE_CONFLICT'
        FROM building_register_profile_publication publication,
             complex_building_register_profile_summary summary,
             complex_building_register_profile projected,
             building_register_profile_hierarchy hierarchy
        WHERE publication.publication_id=:publication
          AND summary.publication_id=publication.publication_id
          AND summary.total_parking_count IS NULL
          AND num_nonnulls(summary.indoor_mechanical_count,summary.outdoor_mechanical_count,
                           summary.indoor_automatic_count,summary.outdoor_automatic_count)=4
          AND projected.projection_run_id=publication.source_projection_run_id
          AND projected.complex_id=summary.complex_id AND projected.source_root_management_key IS NOT NULL
          AND hierarchy.publication_id=publication.publication_id
          AND hierarchy.mgm_bldrgst_pk=projected.source_root_management_key
          AND evidence.publication_id=publication.publication_id
          AND evidence.source_record_key=hierarchy.source_record_key
          AND evidence.field_id='TOT_PKNG_CNT' AND evidence.conflict_status='NONE'
        """;
}
