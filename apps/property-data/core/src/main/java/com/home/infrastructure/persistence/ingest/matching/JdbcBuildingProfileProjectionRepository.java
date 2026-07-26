package com.home.infrastructure.persistence.ingest.matching;

import com.home.application.ingest.buildingprofile.BuildingProfileProjectionCommand;
import com.home.application.ingest.buildingprofile.BuildingProfileProjectionRepository;
import com.home.application.ingest.buildingprofile.BuildingProfileProjectionSummary;
import com.home.domain.complex.buildingprofile.BuildingProfileField;
import com.home.domain.complex.buildingprofile.BuildingProfileProjectionPolicy;
import com.home.domain.complex.buildingprofile.BuildingProfileProjectionUse;
import com.home.domain.complex.buildingprofile.BuildingProfileQualityTier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcBuildingProfileProjectionRepository implements BuildingProfileProjectionRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcBuildingProfileProjectionRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
    }

    @Override
    public BuildingProfileProjectionSummary project(
            BuildingProfileProjectionCommand command, BuildingProfileProjectionPolicy policy) {
        return transaction.execute(ignored -> projectInTransaction(command, policy));
    }

    private BuildingProfileProjectionSummary projectInTransaction(
            BuildingProfileProjectionCommand command, BuildingProfileProjectionPolicy policy) {
        int inserted = jdbc.sql("""
                    INSERT INTO building_register_profile_projection_run
                      (projection_run_id,analysis_run_id,collection_id,parse_run_id,
                       projection_version,minimum_readiness,status)
                    SELECT :projection,analysis_run_id,collection_id,parse_run_id,:version,:threshold,'RUNNING'
                    FROM building_register_profile_analysis_run
                    WHERE analysis_run_id=:analysis AND status='COMPLETED'
                    ON CONFLICT (projection_run_id) DO NOTHING
                    """)
                .param("projection", command.projectionRunId())
                .param("analysis", command.analysisRunId())
                .param("version", command.projectionVersion())
                .param("threshold", policy.minimumReadiness())
                .update();
        ProjectionRun run = loadRun(command.projectionRunId());
        if (inserted == 0 && run == null) {
            throw new IllegalStateException("completed profile analysis run is required");
        }
        if (!run.analysisRunId().equals(command.analysisRunId())
                || !run.projectionVersion().equals(command.projectionVersion())
                || run.minimumReadiness().compareTo(policy.minimumReadiness()) != 0) {
            throw new IllegalArgumentException("projectionRunId is already frozen with different inputs");
        }
        if ("COMPLETED".equals(run.status())) {
            return summary(command.projectionRunId(), true);
        }
        if ("FAILED".equals(run.status())) {
            throw new IllegalStateException("failed profile projection cannot resume");
        }

        String fields = policy.fields().stream()
                .map(BuildingProfileField::name)
                .sorted()
                .collect(Collectors.joining(","));
        List<QualityClassification> quality = jdbc.sql("""
                    SELECT field_id,quality_tier,projectable_complex_readiness
                    FROM building_register_profile_field_quality
                    WHERE analysis_run_id=:analysis AND stratum='WEIGHTED_NATIONAL'
                      AND field_id=ANY(string_to_array(:fields,','))
                    """)
                .param("analysis", command.analysisRunId())
                .param("fields", fields)
                .query((rs, rowNum) -> {
                    BuildingProfileField field = BuildingProfileField.valueOf(rs.getString("field_id"));
                    BuildingProfileQualityTier tier = BuildingProfileQualityTier.valueOf(rs.getString("quality_tier"));
                    return new QualityClassification(
                            field, policy.use(field, tier), rs.getBigDecimal("projectable_complex_readiness"));
                })
                .list();
        if (quality.size() != policy.fields().size()
                || quality.stream().anyMatch(row -> !policy.eligible(row.readiness()))) {
            throw new IllegalStateException("profile analysis does not satisfy all 55 normalized field thresholds");
        }
        int eligibleFieldCount = quality.size();

        String beforeHash = complexSnapshotSha256();
        projectQuality(command, quality);
        projectComplexProfiles(command);
        projectBuildings(command);
        String afterHash = complexSnapshotSha256();
        if (!beforeHash.equals(afterHash)) {
            throw new IllegalStateException("complex changed while building profile projection was running");
        }

        Counts counts = counts(command.projectionRunId());
        jdbc.sql("""
                    UPDATE building_register_profile_projection_run
                    SET status='COMPLETED',complex_snapshot_sha256=:hash,
                        eligible_field_count=:fields,complex_count=:complexes,
                        projectable_complex_count=:projectable,building_count=:buildings,
                        completed_at=now(),failure_reason=NULL
                    WHERE projection_run_id=:projection AND status='RUNNING'
                    """)
                .param("hash", beforeHash)
                .param("fields", eligibleFieldCount)
                .param("complexes", counts.complexCount())
                .param("projectable", counts.projectableComplexCount())
                .param("buildings", counts.buildingCount())
                .param("projection", command.projectionRunId())
                .update();
        return new BuildingProfileProjectionSummary(
                eligibleFieldCount,
                counts.complexCount(),
                counts.projectableComplexCount(),
                counts.buildingCount(),
                beforeHash,
                false);
    }

    private void projectQuality(BuildingProfileProjectionCommand command, List<QualityClassification> quality) {
        for (QualityClassification row : quality) {
            jdbc.sql("""
                    INSERT INTO building_register_profile_projected_quality
                      (projection_run_id,field_id,field_scope,source_record_coverage,building_coverage,
                       pnu_coverage,projectable_complex_readiness,operational_completion,invalid_rate,
                       conflict_rate,wilson_low,wilson_high,quality_tier,projection_use)
                    SELECT :projection,field_id,field_scope,source_record_coverage,building_coverage,
                           pnu_coverage,projectable_complex_readiness,operational_completion,invalid_rate,
                           conflict_rate,wilson_low,wilson_high,quality_tier,:use
                    FROM building_register_profile_field_quality
                    WHERE analysis_run_id=:analysis AND stratum='WEIGHTED_NATIONAL'
                      AND field_id=:field
                    ON CONFLICT (projection_run_id,field_id) DO NOTHING
                    """)
                    .param("projection", command.projectionRunId())
                    .param("analysis", command.analysisRunId())
                    .param("field", row.field().name())
                    .param("use", row.use().name())
                    .update();
        }
    }

    private void projectComplexProfiles(BuildingProfileProjectionCommand command) {
        jdbc.sql("""
                    WITH root_candidates AS MATERIALIZED (
                        SELECT match.complex_id,record.id AS record_id,record.mgm_bldrgst_pk,
                               assignment.scope_key,
                               row_number() OVER (
                                   PARTITION BY match.complex_id
                                   ORDER BY CASE record.endpoint WHEN 'RECAP_TITLE' THEN 0 ELSE 1 END,record.id
                               ) AS source_rank
                        FROM building_register_profile_complex_match match
                        JOIN building_register_profile_scope_assignment assignment
                          ON assignment.analysis_run_id=match.analysis_run_id
                         AND assignment.scope_key=match.scope_key
                         AND assignment.status='RESOLVED'
                        JOIN building_register_profile_record record
                          ON record.id=assignment.profile_record_id
                         AND record.mgm_bldrgst_pk=assignment.root_management_key
                         AND record.endpoint IN ('RECAP_TITLE','TITLE')
                        WHERE match.analysis_run_id=:analysis AND match.projectable
                    ), root_values AS MATERIALIZED (
                        SELECT root.complex_id,root.scope_key,root.mgm_bldrgst_pk,
                               max(value.integer_value) FILTER (WHERE value.field_id='ATCH_BLD_CNT') AS atch_bld_cnt,
                               max(value.text_value) FILTER (WHERE value.field_id='BJDONG_CD') AS bjdong_cd,
                               max(value.text_value) FILTER (WHERE value.field_id='BLD_NM') AS bld_nm,
                               max(value.text_value) FILTER (WHERE value.field_id='BUN') AS bun,
                               max(value.integer_value) FILTER (WHERE value.field_id='BYLOT_CNT') AS bylot_cnt,
                               max(value.date_value) FILTER (WHERE value.field_id='CRTN_DAY') AS crtn_day,
                               max(value.integer_value) FILTER (WHERE value.field_id='FMLY_CNT') AS fmly_cnt,
                               max(value.integer_value) FILTER (WHERE value.field_id='HHLD_CNT') AS hhld_cnt,
                               max(value.text_value) FILTER (WHERE value.field_id='JI') AS ji,
                               max(value.text_value) FILTER (WHERE value.field_id='NEW_PLAT_PLC') AS new_plat_plc,
                               max(value.decimal_value) FILTER (WHERE value.field_id='PLAT_AREA') AS plat_area,
                               max(value.text_value) FILTER (WHERE value.field_id='PLAT_GB_CD') AS plat_gb_cd,
                               max(value.text_value) FILTER (WHERE value.field_id='PLAT_PLC') AS plat_plc,
                               max(value.date_value) FILTER (WHERE value.field_id='PMS_DAY') AS pms_day,
                               max(value.text_value) FILTER (WHERE value.field_id='ROAD_BJDONG_CD') AS road_bjdong_cd,
                               max(value.text_value) FILTER (WHERE value.field_id='ROAD_CD') AS road_cd,
                               max(value.text_value) FILTER (WHERE value.field_id='ROAD_MAIN_NO') AS road_main_no,
                               max(value.text_value) FILTER (WHERE value.field_id='ROAD_SUB_NO') AS road_sub_no,
                               max(value.text_value) FILTER (WHERE value.field_id='ROAD_UNDERGROUND_CD') AS road_underground_cd,
                               max(value.text_value) FILTER (WHERE value.field_id='SIGUNGU_CD') AS sigungu_cd,
                               max(value.date_value) FILTER (WHERE value.field_id='STCNS_DAY') AS stcns_day,
                               max(value.decimal_value) FILTER (WHERE value.field_id='TOT_DONG_TOT_AREA') AS tot_dong_tot_area,
                               max(value.date_value) FILTER (WHERE value.field_id='USE_APR_DAY') AS use_apr_day
                        FROM root_candidates root
                        LEFT JOIN building_register_profile_value value
                          ON value.profile_record_id=root.record_id
                         AND value.value_state IN ('ZERO','POSITIVE','VALID')
                        WHERE root.source_rank=1
                        GROUP BY root.complex_id,root.scope_key,root.mgm_bldrgst_pk
                    )
                    INSERT INTO complex_building_register_profile
                      (projection_run_id,complex_id,analysis_run_id,collection_id,assignment_status,
                       projectable,failure_reason,source_scope_key,source_root_management_key,
                       atch_bld_cnt,bjdong_cd,bld_nm,bun,bylot_cnt,crtn_day,fmly_cnt,hhld_cnt,ji,
                       new_plat_plc,plat_area,plat_gb_cd,plat_plc,pms_day,road_bjdong_cd,road_cd,
                       road_main_no,road_sub_no,road_underground_cd,sigungu_cd,stcns_day,
                       tot_dong_tot_area,use_apr_day)
                    SELECT :projection,match.complex_id,match.analysis_run_id,match.collection_id,match.status,
                           match.projectable,match.failure_reason,root.scope_key,root.mgm_bldrgst_pk,
                           root.atch_bld_cnt,root.bjdong_cd,root.bld_nm,root.bun,root.bylot_cnt,root.crtn_day,
                           root.fmly_cnt,root.hhld_cnt,root.ji,root.new_plat_plc,root.plat_area,root.plat_gb_cd,
                           root.plat_plc,root.pms_day,root.road_bjdong_cd,root.road_cd,root.road_main_no,
                           root.road_sub_no,root.road_underground_cd,root.sigungu_cd,root.stcns_day,
                           root.tot_dong_tot_area,root.use_apr_day
                    FROM building_register_profile_complex_match match
                    LEFT JOIN root_values root ON root.complex_id=match.complex_id
                    WHERE match.analysis_run_id=:analysis
                    ON CONFLICT (projection_run_id,complex_id) DO NOTHING
                    """)
                .param("projection", command.projectionRunId())
                .param("analysis", command.analysisRunId())
                .update();
    }

    private void projectBuildings(BuildingProfileProjectionCommand command) {
        jdbc.sql("""
                    WITH title_records AS MATERIALIZED (
                        SELECT match.complex_id,record.id AS record_id,record.mgm_bldrgst_pk,
                               record.mgm_up_bldrgst_pk,record.content_sha256
                        FROM building_register_profile_complex_match match
                        JOIN building_register_profile_scope_assignment assignment
                          ON assignment.analysis_run_id=match.analysis_run_id
                         AND assignment.scope_key=match.scope_key
                         AND assignment.status='RESOLVED'
                        JOIN building_register_profile_record record
                          ON record.id=assignment.profile_record_id
                         AND record.endpoint='TITLE'
                         AND record.regstr_kind_cd IN ('2','3')
                        WHERE match.analysis_run_id=:analysis AND match.projectable
                    )
                    INSERT INTO complex_building_register_building
                      (projection_run_id,complex_id,source_management_key,source_parent_management_key,
                       source_content_sha256,main_atch_gb_cd,main_atch_gb_cd_nm,dong_nm,atch_bld_area,ho_cnt,
                       indr_mech_utcnt,indr_mech_area,oudr_mech_utcnt,oudr_mech_area,indr_auto_utcnt,
                       indr_auto_area,oudr_auto_utcnt,oudr_auto_area,heit,grnd_flr_cnt,ugrnd_flr_cnt,
                       ride_use_elvt_cnt,emgen_use_elvt_cnt,strct_cd,strct_cd_nm,etc_strct,roof_cd,
                       roof_cd_nm,etc_roof,main_purps_cd,main_purps_cd_nm,etc_purps,
                       rserthqk_dsgn_apply_yn,engr_rat,engr_epi,gn_bld_cert,itg_bld_cert)
                    SELECT :projection,title.complex_id,title.mgm_bldrgst_pk,title.mgm_up_bldrgst_pk,
                           title.content_sha256,
                           max(value.text_value) FILTER (WHERE value.field_id='MAIN_ATCH_GB_CD'),
                           max(value.text_value) FILTER (WHERE value.field_id='MAIN_ATCH_GB_CD_NM'),
                           max(value.text_value) FILTER (WHERE value.field_id='DONG_NM'),
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
                           max(value.decimal_value) FILTER (WHERE value.field_id='ENGR_RAT'),
                           max(value.decimal_value) FILTER (WHERE value.field_id='ENGR_EPI'),
                           max(value.decimal_value) FILTER (WHERE value.field_id='GN_BLD_CERT'),
                           max(value.decimal_value) FILTER (WHERE value.field_id='ITG_BLD_CERT')
                    FROM title_records title
                    LEFT JOIN building_register_profile_value value
                      ON value.profile_record_id=title.record_id
                     AND value.value_state IN ('ZERO','POSITIVE','VALID')
                    GROUP BY title.complex_id,title.record_id,title.mgm_bldrgst_pk,
                             title.mgm_up_bldrgst_pk,title.content_sha256
                    ON CONFLICT (projection_run_id,complex_id,source_management_key) DO NOTHING
                    """)
                .param("projection", command.projectionRunId())
                .param("analysis", command.analysisRunId())
                .update();
    }

    private ProjectionRun loadRun(UUID projectionRunId) {
        return jdbc.sql("""
                    SELECT analysis_run_id,projection_version,minimum_readiness,status
                    FROM building_register_profile_projection_run
                    WHERE projection_run_id=:projection FOR UPDATE
                    """)
                .param("projection", projectionRunId)
                .query((rs, rowNum) -> new ProjectionRun(
                        rs.getObject("analysis_run_id", UUID.class),
                        rs.getString("projection_version"),
                        rs.getBigDecimal("minimum_readiness"),
                        rs.getString("status")))
                .optional()
                .orElse(null);
    }

    private Counts counts(UUID projectionRunId) {
        return jdbc.sql("""
                    SELECT count(*)::integer AS complex_count,
                           count(*) FILTER (WHERE projectable)::integer AS projectable_count,
                           (SELECT count(*)::integer FROM complex_building_register_building
                            WHERE projection_run_id=:projection) AS building_count
                    FROM complex_building_register_profile
                    WHERE projection_run_id=:projection
                    """)
                .param("projection", projectionRunId)
                .query((rs, rowNum) -> new Counts(
                        rs.getInt("complex_count"), rs.getInt("projectable_count"), rs.getInt("building_count")))
                .single();
    }

    private BuildingProfileProjectionSummary summary(UUID projectionRunId, boolean alreadyCompleted) {
        return jdbc.sql("""
                    SELECT eligible_field_count,complex_count,projectable_complex_count,
                           building_count,complex_snapshot_sha256
                    FROM building_register_profile_projection_run WHERE projection_run_id=:projection
                    """)
                .param("projection", projectionRunId)
                .query((rs, rowNum) -> new BuildingProfileProjectionSummary(
                        rs.getInt("eligible_field_count"),
                        rs.getInt("complex_count"),
                        rs.getInt("projectable_complex_count"),
                        rs.getInt("building_count"),
                        rs.getString("complex_snapshot_sha256"),
                        alreadyCompleted))
                .single();
    }

    private String complexSnapshotSha256() {
        try {
            jdbc.sql("SELECT set_config('TimeZone','UTC',true)")
                    .query(String.class)
                    .single();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            jdbc.sql("SELECT row_to_json(value)::text FROM complex value ORDER BY id")
                    .query(String.class)
                    .list()
                    .forEach(row -> {
                        digest.update(row.getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) '\n');
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ProjectionRun(
            UUID analysisRunId, String projectionVersion, java.math.BigDecimal minimumReadiness, String status) {}

    private record QualityClassification(
            BuildingProfileField field, BuildingProfileProjectionUse use, java.math.BigDecimal readiness) {}

    private record Counts(int complexCount, int projectableComplexCount, int buildingCount) {}
}
