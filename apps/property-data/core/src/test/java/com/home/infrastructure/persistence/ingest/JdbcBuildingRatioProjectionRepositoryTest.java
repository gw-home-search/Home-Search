package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.domain.complex.buildingregister.BuildingRatioProjectionOutcome;
import com.home.infrastructure.persistence.ingest.matching.JdbcBuildingRatioProjectionRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcBuildingRatioProjectionRepositoryTest extends JdbcMigrationTestSupport {
    private static final UUID COLLECTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174140");
    private static final UUID REQUEST_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174141");
    private JdbcBuildingRatioProjectionRepository repository;
    private long bcCandidateId;
    private long vlCandidateId;

    @BeforeEach
    void setUp() {
        flyway(null).clean();
        flyway(null).migrate();
        jdbcClient
                .sql("INSERT INTO parcel(id,pnu,address) VALUES (1001,'1168010300101400001','Sample')")
                .update();
        jdbcClient.sql("""
                    INSERT INTO complex
                        (id,parcel_id,complex_pk,name,vl_rat,metadata_status,metadata_source)
                    VALUES (501,1001,'RTMS:501','Sample',70.00,'FAILED','ODC_APT')
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO building_register_collection_campaign
                    (collection_id,mode,strategy,to_complex_id,status,completed_at)
                    VALUES (:id,'missing','ADAPTIVE',1000,'COMPLETED',now())
                    """).param("id", COLLECTION_ID).update();
        long matchId = match("UNIQUE_ROOT", true);
        bcCandidateId = candidate(matchId, "BUILDING_COVERAGE_RATIO", "20.12");
        vlCandidateId = candidate(matchId, "FLOOR_AREA_RATIO", "80.34");
        repository = new JdbcBuildingRatioProjectionRepository(jdbcClient, transactionTemplate);
    }

    @Test
    void appliesOnlyNullFieldAndPreservesExistingMetadataState() {
        assertThat(repository.project(REQUEST_ID, bcCandidateId)).isEqualTo(BuildingRatioProjectionOutcome.APPLIED);
        assertThat(repository.project(REQUEST_ID, vlCandidateId))
                .isEqualTo(BuildingRatioProjectionOutcome.SKIPPED_EXISTING_CONFLICT);

        var state = jdbcClient
                .sql("SELECT bc_rat,vl_rat,metadata_status,metadata_source FROM complex WHERE id=501")
                .query((resultSet, rowNum) -> new Object[] {
                    resultSet.getBigDecimal("bc_rat"),
                    resultSet.getBigDecimal("vl_rat"),
                    resultSet.getString("metadata_status"),
                    resultSet.getString("metadata_source")
                })
                .single();
        assertThat((BigDecimal) state[0]).isEqualByComparingTo("20.12");
        assertThat((BigDecimal) state[1]).isEqualByComparingTo("70.00");
        assertThat(state[2]).isEqualTo("FAILED");
        assertThat(state[3]).isEqualTo("ODC_APT");
    }

    @Test
    void repeatedRequestReturnsStoredOutcomeWithoutDuplicateHistory() {
        assertThat(repository.project(REQUEST_ID, bcCandidateId)).isEqualTo(BuildingRatioProjectionOutcome.APPLIED);
        assertThat(repository.project(REQUEST_ID, bcCandidateId)).isEqualTo(BuildingRatioProjectionOutcome.APPLIED);

        assertThat(jdbcClient
                        .sql("SELECT count(*) FROM building_ratio_projection WHERE request_id=:request")
                        .param("request", REQUEST_ID)
                        .query(Integer.class)
                        .single())
                .isOne();
    }

    @Test
    void recordsAlreadyEqualWithoutUpdatingExistingValue() {
        jdbcClient.sql("UPDATE complex SET vl_rat=80.34 WHERE id=501").update();

        assertThat(repository.project(REQUEST_ID, vlCandidateId))
                .isEqualTo(BuildingRatioProjectionOutcome.ALREADY_EQUAL);
    }

    @Test
    void neverProjectsSharedRecapCandidate() {
        jdbcClient.sql("""
                    UPDATE building_register_complex_match
                    SET scope='SHARED_RECAP',projectable=false,match_path=NULL
                    WHERE collection_id=:collection AND complex_id=501
                    """).param("collection", COLLECTION_ID).update();

        assertThat(repository.project(REQUEST_ID, bcCandidateId))
                .isEqualTo(BuildingRatioProjectionOutcome.SKIPPED_SHARED_SCOPE);
        assertThat(jdbcClient
                        .sql("SELECT bc_rat FROM complex WHERE id=501")
                        .query(BigDecimal.class)
                        .optional())
                .isEmpty();
    }

    @Test
    void recordsSourceConflictWithoutApplyingRepresentativeCandidate() {
        long matchId = jdbcClient
                .sql("""
                    SELECT id FROM building_register_complex_match
                    WHERE collection_id=:collection AND complex_id=501
                    """)
                .param("collection", COLLECTION_ID)
                .query(Long.class)
                .single();
        long conflictCandidate =
                jdbcClient.sql("""
                    INSERT INTO building_ratio_candidate
                        (match_id,field,method,value,projected_value,status,selected)
                    VALUES (:match,'BUILDING_COVERAGE_RATIO','TITLE_AGGREGATE_CALC',33.33,33.33,
                            'SOURCE_CONFLICT',false)
                    RETURNING id
                    """).param("match", matchId).query(Long.class).single();

        assertThat(repository.project(REQUEST_ID, conflictCandidate))
                .isEqualTo(BuildingRatioProjectionOutcome.SKIPPED_SOURCE_CONFLICT);
        assertThat(jdbcClient
                        .sql("SELECT bc_rat FROM complex WHERE id=501")
                        .query(BigDecimal.class)
                        .optional())
                .isEmpty();
    }

    private long match(String scope, boolean projectable) {
        return jdbcClient
                .sql("""
                    INSERT INTO building_register_complex_match
                        (collection_id,complex_id,pnu,root_management_key,scope,status,match_path,projectable)
                    VALUES (:collection,501,'1168010300101400001',:root,:scope,'RESOLVED',:path,:projectable)
                    RETURNING id
                    """)
                .param("collection", COLLECTION_ID)
                .param("root", scope + "-ROOT")
                .param("scope", scope)
                .param("path", projectable ? "EXISTING_KEY" : null)
                .param("projectable", projectable)
                .query(Long.class)
                .single();
    }

    private long candidate(long matchId, String field, String value) {
        return jdbcClient
                .sql("""
                    INSERT INTO building_ratio_candidate
                        (match_id,field,method,value,projected_value,status,selected)
                    VALUES (:match,:field,'RECAP_DIRECT',:value,:value,'VALID',true)
                    RETURNING id
                    """)
                .param("match", matchId)
                .param("field", field)
                .param("value", new BigDecimal(value))
                .query(Long.class)
                .single();
    }
}
