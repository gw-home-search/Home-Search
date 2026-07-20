package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class JdbcBuildingRegisterEvidenceMigrationTest extends JdbcMigrationTestSupport {
    private static final UUID COLLECTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174100");
    private static final UUID REQUEST_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174101");

    @Test
    @DisplayName("건축물대장 증거 schema 제약을 검증한다")
    void createsAppendOnlyBuildingRegisterEvidenceSchema() {
        flyway(null).clean();
        flyway(null).migrate();

        assertThat(List.of(
                        "building_register_collection_campaign",
                        "building_register_collection_target",
                        "building_register_endpoint_snapshot",
                        "building_register_raw_page",
                        "building_register_record_snapshot",
                        "building_register_complex_match",
                        "building_register_match_evidence",
                        "building_ratio_candidate",
                        "building_ratio_candidate_input",
                        "building_ratio_projection"))
                .allMatch(table -> regclass(table) != null);
    }

    @Test
    @DisplayName("건축물대장 증거 schema 제약을 검증한다")
    void enforcesFrozenTargetPnuAndCampaignUniqueness() {
        migrateAndSeedTarget();

        assertThatThrownBy(() -> insertTarget(2, 501, "1168010300101400001"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertTarget(3, 502, "invalid-pnu"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("건축물대장 증거 schema 제약을 검증한다")
    void keepsRawAttemptsButAllowsOnlyOneCompletedPagePerSnapshot() {
        migrateAndSeedTarget();
        long snapshotId = insertEndpointSnapshot();
        insertRawPage(snapshotId, 1, 1, "PROVIDER_FAILED");
        insertRawPage(snapshotId, 1, 2, "PARSED");

        assertThatThrownBy(() -> insertRawPage(snapshotId, 1, 3, "EMPTY"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("건축물대장 증거 schema 제약을 검증한다")
    void requiresRawPageBeforeNormalizedRecordAndPreventsEvidenceDeletion() {
        migrateAndSeedTarget();
        long snapshotId = insertEndpointSnapshot();

        assertThatThrownBy(() -> jdbcClient.sql("""
                            INSERT INTO building_register_record_snapshot
                            (raw_page_id,item_index,pnu,endpoint,mgm_bldrgst_pk,regstr_kind_cd)
                            VALUES (999999,0,'1168010300101400001','TITLE','TITLE-1','3')
                            """).update()).isInstanceOf(DataIntegrityViolationException.class);

        long rawPageId = insertRawPage(snapshotId, 1, 1, "PARSED");
        jdbcClient.sql("""
                    INSERT INTO building_register_record_snapshot
                    (raw_page_id,item_index,pnu,endpoint,mgm_bldrgst_pk,regstr_kind_cd)
                    VALUES (:raw,0,'1168010300101400001','TITLE','TITLE-1','3')
                    """).param("raw", rawPageId).update();

        assertThatThrownBy(() -> jdbcClient
                        .sql("DELETE FROM building_register_raw_page WHERE id=:id")
                        .param("id", rawPageId)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("건축물대장 증거 schema 제약을 검증한다")
    void allowsAtMostOneSelectedCandidatePerMatchAndField() {
        migrateAndSeedTarget();
        long matchId = insertMatch();
        insertCandidate(matchId, "RECAP_DIRECT", true);

        assertThatThrownBy(() -> insertCandidate(matchId, "TITLE_AGGREGATE_CALC", true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void migrateAndSeedTarget() {
        flyway(null).clean();
        flyway(null).migrate();
        jdbcClient
                .sql("INSERT INTO parcel(id,pnu,address) VALUES (1001,'1168010300101400001','Sample')")
                .update();
        jdbcClient
                .sql("INSERT INTO complex(id,parcel_id,complex_pk,name) VALUES (501,1001,'RTMS:501','Sample')")
                .update();
        jdbcClient
                .sql("INSERT INTO parcel(id,pnu,address) VALUES (1002,'1168010300101400002','Other')")
                .update();
        jdbcClient
                .sql("INSERT INTO complex(id,parcel_id,complex_pk,name) VALUES (502,1002,'RTMS:502','Other')")
                .update();
        jdbcClient.sql("""
                    INSERT INTO building_register_collection_campaign
                    (collection_id,mode,strategy,to_complex_id,status)
                    VALUES (:collection,'missing','ADAPTIVE',1000,'COLLECTING')
                    """).param("collection", COLLECTION_ID).update();
        insertTarget(1, 501, "1168010300101400001");
    }

    private void insertTarget(long ordinal, long complexId, String pnu) {
        jdbcClient
                .sql("""
                    INSERT INTO building_register_collection_target
                    (collection_id,target_ordinal,complex_id,pnu)
                    VALUES (:collection,:ordinal,:complex,:pnu)
                    """)
                .param("collection", COLLECTION_ID)
                .param("ordinal", ordinal)
                .param("complex", complexId)
                .param("pnu", pnu)
                .update();
    }

    private long insertEndpointSnapshot() {
        return jdbcClient
                .sql("""
                    INSERT INTO building_register_endpoint_snapshot
                    (collection_id,pnu,endpoint,run_date,page_size,attempt_no,status)
                    VALUES (:collection,'1168010300101400001','RECAP_TITLE',DATE '2026-07-20',100,1,'ACTIVE')
                    RETURNING id
                    """)
                .param("collection", COLLECTION_ID)
                .query(Long.class)
                .single();
    }

    private long insertRawPage(long snapshotId, int pageNo, int attemptNo, String status) {
        return jdbcClient
                .sql("""
                    INSERT INTO building_register_raw_page
                    (endpoint_snapshot_id,request_id,page_no,attempt_no,status,body_sha256,byte_count,http_status)
                    VALUES (:snapshot,:request,:page,:attempt,:status,repeat('a',64),2,200)
                    RETURNING id
                    """)
                .param("snapshot", snapshotId)
                .param("request", REQUEST_ID)
                .param("page", pageNo)
                .param("attempt", attemptNo)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private long insertMatch() {
        return jdbcClient
                .sql("""
                    INSERT INTO building_register_complex_match
                    (collection_id,complex_id,pnu,root_management_key,scope,status,match_path,projectable)
                    VALUES (:collection,501,'1168010300101400001','ROOT-1','UNIQUE_ROOT','RESOLVED','EXISTING_KEY',true)
                    RETURNING id
                    """)
                .param("collection", COLLECTION_ID)
                .query(Long.class)
                .single();
    }

    private void insertCandidate(long matchId, String method, boolean selected) {
        jdbcClient
                .sql("""
                    INSERT INTO building_ratio_candidate
                    (match_id,field,method,value,projected_value,status,selected)
                    VALUES (:match,'BUILDING_COVERAGE_RATIO',:method,20.00000000,20.00,'VALID',:selected)
                    """)
                .param("match", matchId)
                .param("method", method)
                .param("selected", selected)
                .update();
    }

    private String regclass(String name) {
        return jdbcClient
                .sql("SELECT to_regclass(:name)::text")
                .param("name", name)
                .query(String.class)
                .optional()
                .orElse(null);
    }
}
