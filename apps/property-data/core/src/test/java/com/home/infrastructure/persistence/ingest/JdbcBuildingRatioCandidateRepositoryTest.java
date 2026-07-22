package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.domain.complex.buildingregister.BuildingRatioEvaluationContext;
import com.home.domain.complex.buildingregister.BuildingRatioEvaluator;
import com.home.domain.complex.buildingregister.BuildingRatioScope;
import com.home.domain.complex.buildingregister.BuildingRegisterCollectionStrategy;
import com.home.domain.complex.buildingregister.BuildingRegisterRecord;
import com.home.infrastructure.persistence.ingest.matching.JdbcBuildingRatioCandidateRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcBuildingRatioCandidateRepositoryTest extends JdbcMigrationTestSupport {
    private static final UUID COLLECTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174150");
    private JdbcBuildingRatioCandidateRepository repository;
    private long matchId;
    private long recordId;

    @BeforeEach
    void setUp() {
        flyway(null).clean();
        flyway(null).migrate();
        jdbcClient
                .sql("INSERT INTO parcel(id,pnu,address) VALUES (1001,'1168010300101400001','Sample')")
                .update();
        jdbcClient
                .sql("INSERT INTO complex(id,parcel_id,complex_pk,name) VALUES (501,1001,'RTMS:501','Sample')")
                .update();
        jdbcClient.sql("""
                    INSERT INTO building_register_collection_campaign
                    (collection_id,mode,strategy,to_complex_id,status,completed_at)
                    VALUES (:id,'missing','ADAPTIVE',1000,'COMPLETED',now())
                    """).param("id", COLLECTION_ID).update();
        long snapshot =
                jdbcClient.sql("""
                    INSERT INTO building_register_endpoint_snapshot
                    (collection_id,pnu,endpoint,run_date,page_size,attempt_no,status,total_count,completed_at)
                    VALUES (:id,'1168010300101400001','RECAP_TITLE',DATE '2026-07-20',100,1,'PARSED',1,now())
                    RETURNING id
                    """).param("id", COLLECTION_ID).query(Long.class).single();
        long raw = jdbcClient
                .sql("""
                    INSERT INTO building_register_raw_page
                    (endpoint_snapshot_id,request_id,page_no,attempt_no,status,body_sha256,byte_count,http_status,finalized_at)
                    VALUES (:snapshot,gen_random_uuid(),1,1,'PARSED',repeat('a',64),2,200,now()) RETURNING id
                    """)
                .param("snapshot", snapshot)
                .query(Long.class)
                .single();
        recordId = jdbcClient.sql("""
                    INSERT INTO building_register_record_snapshot
                    (raw_page_id,item_index,pnu,endpoint,mgm_bldrgst_pk,regstr_kind_cd,plat_area,arch_area,
                     vl_rat_estm_tot_area,bc_rat,vl_rat)
                    VALUES (:raw,0,'1168010300101400001','RECAP_TITLE','ROOT-1','1',1000,200,800,20,80)
                    RETURNING id
                    """).param("raw", raw).query(Long.class).single();
        matchId =
                jdbcClient.sql("""
                    INSERT INTO building_register_complex_match
                    (collection_id,complex_id,pnu,root_management_key,scope,status,match_path,projectable)
                    VALUES (:id,501,'1168010300101400001','ROOT-1','UNIQUE_ROOT','RESOLVED','EXISTING_KEY',true)
                    RETURNING id
                    """).param("id", COLLECTION_ID).query(Long.class).single();
        repository = new JdbcBuildingRatioCandidateRepository(jdbcClient, transactionTemplate);
    }

    @Test
    @DisplayName("건축물대장 비율 후보 저장을 검증한다")
    void storesEveryCandidateSelectedCandidateAndSourceInputsIdempotently() {
        BuildingRegisterRecord recap = new BuildingRegisterRecord(
                "ROOT-1",
                null,
                1,
                null,
                "02000",
                new BigDecimal("1000"),
                new BigDecimal("200"),
                null,
                new BigDecimal("800"),
                new BigDecimal("20"),
                new BigDecimal("80"));
        var evaluation = new BuildingRatioEvaluator()
                .evaluate(new BuildingRatioEvaluationContext(
                        BuildingRegisterCollectionStrategy.ADAPTIVE,
                        BuildingRatioScope.UNIQUE_ROOT,
                        recap,
                        List.of(),
                        Set.of(),
                        true));

        var first = repository.record(matchId, evaluation, Map.of("ROOT-1", recordId));
        var second = repository.record(matchId, evaluation, Map.of("ROOT-1", recordId));

        assertThat(first.selectedCandidateIds()).hasSize(2).isEqualTo(second.selectedCandidateIds());
        assertThat(jdbcClient
                        .sql("SELECT count(*) FROM building_ratio_candidate WHERE match_id=:match")
                        .param("match", matchId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(4);
        assertThat(jdbcClient
                        .sql("SELECT count(DISTINCT candidate_id) FROM building_ratio_candidate_input")
                        .query(Integer.class)
                        .single())
                .isEqualTo(4);
    }

    @Test
    @DisplayName("건축물대장 비율 후보 저장을 검증한다")
    void normalizesRepeatingCalculatedValuesBeforeIdempotencyLookup() {
        BuildingRegisterRecord recap = new BuildingRegisterRecord(
                "ROOT-1",
                null,
                1,
                null,
                "02000",
                new BigDecimal("3"),
                new BigDecimal("1"),
                null,
                new BigDecimal("1"),
                null,
                null);
        var evaluation = new BuildingRatioEvaluator()
                .evaluate(BuildingRatioEvaluationContext.uniqueRoot(
                        BuildingRegisterCollectionStrategy.ADAPTIVE, recap, List.of(), Set.of(), true));

        repository.record(matchId, evaluation, Map.of("ROOT-1", recordId));
        repository.record(matchId, evaluation, Map.of("ROOT-1", recordId));

        assertThat(jdbcClient
                        .sql("SELECT count(*) FROM building_ratio_candidate WHERE match_id=:match")
                        .param("match", matchId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("건축물대장 비율 후보 저장을 검증한다")
    void recordsHybridRecapNumeratorAndTitleDenominatorRolesSeparately() {
        long titleRecordId =
                jdbcClient.sql("""
                    INSERT INTO building_register_record_snapshot
                    (raw_page_id,item_index,pnu,endpoint,mgm_bldrgst_pk,mgm_up_bldrgst_pk,regstr_kind_cd,plat_area,
                     arch_area,vl_rat_estm_tot_area)
                    SELECT raw_page_id,1,pnu,'TITLE','TITLE-1','ROOT-1','3',1000,200,800
                    FROM building_register_record_snapshot WHERE id=:record
                    RETURNING id
                    """).param("record", recordId).query(Long.class).single();
        BuildingRegisterRecord recap = new BuildingRegisterRecord(
                "ROOT-1", null, 1, null, "02000", null, new BigDecimal("200"), null, new BigDecimal("800"), null, null);
        BuildingRegisterRecord title = new BuildingRegisterRecord(
                "TITLE-1",
                "ROOT-1",
                3,
                null,
                "03000",
                new BigDecimal("1000"),
                new BigDecimal("200"),
                null,
                new BigDecimal("800"),
                null,
                null);
        var evaluation = new BuildingRatioEvaluator()
                .evaluate(BuildingRatioEvaluationContext.uniqueRoot(
                        BuildingRegisterCollectionStrategy.ADAPTIVE, recap, List.of(title), Set.of("TITLE-1"), true));

        repository.record(matchId, evaluation, Map.of("ROOT-1", recordId, "TITLE-1", titleRecordId));

        assertThat(jdbcClient
                        .sql("""
                            SELECT r.mgm_bldrgst_pk, i.input_role
                            FROM building_ratio_candidate_input i
                            JOIN building_ratio_candidate c ON c.id=i.candidate_id
                            JOIN building_register_record_snapshot r ON r.id=i.record_snapshot_id
                            WHERE c.match_id=:match AND c.method='RECAP_NUMERATOR_TITLE_DENOMINATOR'
                            ORDER BY r.mgm_bldrgst_pk, i.input_role
                            """)
                        .param("match", matchId)
                        .query((rs, rowNum) -> rs.getString(1) + ":" + rs.getString(2))
                        .list())
                .containsExactly("ROOT-1:NUMERATOR", "ROOT-1:NUMERATOR", "TITLE-1:DENOMINATOR", "TITLE-1:DENOMINATOR");
    }

    @Test
    @DisplayName("건축물대장 비율 후보 저장을 검증한다")
    void clearsStaleSelectionWhenReevaluationFindsSourceConflict() {
        BuildingRegisterRecord agreed = recap("20", "200");
        BuildingRegisterRecord conflicting = recap("21", "200");
        BuildingRatioEvaluator evaluator = new BuildingRatioEvaluator();

        repository.record(
                matchId,
                evaluator.evaluate(BuildingRatioEvaluationContext.uniqueRoot(
                        BuildingRegisterCollectionStrategy.ADAPTIVE, agreed, List.of(), Set.of(), true)),
                Map.of("ROOT-1", recordId));
        repository.record(
                matchId,
                evaluator.evaluate(BuildingRatioEvaluationContext.uniqueRoot(
                        BuildingRegisterCollectionStrategy.ADAPTIVE, conflicting, List.of(), Set.of(), true)),
                Map.of("ROOT-1", recordId));

        assertThat(jdbcClient
                        .sql("""
                            SELECT count(*) FROM building_ratio_candidate
                            WHERE match_id=:match AND field='BUILDING_COVERAGE_RATIO' AND selected
                            """)
                        .param("match", matchId)
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(jdbcClient
                        .sql("""
                            SELECT count(*) FROM building_ratio_candidate
                            WHERE match_id=:match AND field='BUILDING_COVERAGE_RATIO' AND status='SOURCE_CONFLICT'
                            """)
                        .param("match", matchId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    private BuildingRegisterRecord recap(String directBuildingRatio, String archArea) {
        return new BuildingRegisterRecord(
                "ROOT-1",
                null,
                1,
                null,
                "02000",
                new BigDecimal("1000"),
                new BigDecimal(archArea),
                null,
                new BigDecimal("800"),
                new BigDecimal(directBuildingRatio),
                new BigDecimal("80"));
    }
}
