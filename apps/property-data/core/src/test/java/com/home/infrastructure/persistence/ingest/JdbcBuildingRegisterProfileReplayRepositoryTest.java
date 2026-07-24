package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.ingest.buildingprofile.BuildingProfileRawPage;
import com.home.application.ingest.buildingprofile.BuildingProfileReplayCommand;
import com.home.application.ingest.buildingprofile.BuildingProfileReplayService;
import com.home.domain.complex.buildingprofile.BuildingProfileParseStatus;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import com.home.domain.complex.buildingregister.BuildingRegisterRawPageStatus;
import com.home.infrastructure.external.complex.BuildingRegisterProfileJsonParser;
import com.home.infrastructure.persistence.ingest.matching.JdbcBuildingProfileReplayRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JdbcBuildingRegisterProfileReplayRepositoryTest extends JdbcMigrationTestSupport {
    private static final UUID COLLECTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174220");
    private static final UUID PARSE_V1 = UUID.fromString("123e4567-e89b-12d3-a456-426614174221");
    private JdbcBuildingProfileReplayRepository repository;
    private long rawPageId;

    @BeforeEach
    void setUp() {
        flyway(null).clean();
        flyway(null).migrate();
        jdbcClient.sql("""
                    INSERT INTO building_register_collection_campaign
                      (collection_id,mode,strategy,to_complex_id,status)
                    VALUES (:id,'missing','ADAPTIVE',1,'COLLECTING')
                    """).param("id", COLLECTION_ID).update();
        long snapshotId =
                jdbcClient.sql("""
                    INSERT INTO building_register_endpoint_snapshot
                      (collection_id,pnu,endpoint,run_date,page_size,attempt_no,status,total_count,completed_at)
                    VALUES (:id,'1168010300101400001','TITLE',DATE '2026-07-21',100,1,'PARSED',1,now())
                    RETURNING id
                    """).param("id", COLLECTION_ID).query(Long.class).single();
        String body = """
                {"response":{"header":{"resultCode":"00"},"body":{"pageNo":1,"numOfRows":100,
                "totalCount":1,"items":{"item":{"mgmBldrgstPk":"TITLE-1","regstrKindCd":"3",
                "platArea":"100.5","futureKey":"observed"}}}}}
                """;
        rawPageId = jdbcClient
                .sql("""
                    INSERT INTO building_register_raw_page
                      (endpoint_snapshot_id,request_id,page_no,attempt_no,status,response_body,body_sha256,byte_count,http_status,finalized_at)
                    VALUES (:snapshot,gen_random_uuid(),1,1,'PARSED',:body,repeat('a',64),octet_length(:body),200,now())
                    RETURNING id
                    """)
                .param("snapshot", snapshotId)
                .param("body", body)
                .query(Long.class)
                .single();
        repository = new JdbcBuildingProfileReplayRepository(jdbcClient, transactionTemplate);
    }

    @Test
    @DisplayName("기존 raw를 외부 호출 없이 parser version별로 재분석하고 이전 결과를 유지한다")
    void replaysSameRawIntoIndependentParserVersions() {
        BuildingProfileReplayService service = new BuildingProfileReplayService(
                repository,
                new BuildingRegisterProfileJsonParser(JsonMapper.builder().build()));

        var first = service.replay(new BuildingProfileReplayCommand(COLLECTION_ID, PARSE_V1, "PROFILE_V1", 10));
        UUID parseV2 = UUID.fromString("123e4567-e89b-12d3-a456-426614174222");
        var second = service.replay(new BuildingProfileReplayCommand(COLLECTION_ID, parseV2, "PROFILE_V2", 10));

        assertThat(first.completed()).isTrue();
        assertThat(second.completed()).isTrue();
        assertThat(jdbcClient
                        .sql("SELECT count(*) FROM building_register_profile_record WHERE raw_page_id=:raw")
                        .param("raw", rawPageId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        assertThat(jdbcClient
                        .sql(
                                "SELECT count(*) FROM building_register_profile_schema_observation WHERE source_key='futureKey'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        assertThat(jdbcClient
                        .sql("SELECT status FROM building_register_raw_page WHERE id=:raw")
                        .param("raw", rawPageId)
                        .query(String.class)
                        .single())
                .isEqualTo("PARSED");

        long driftRaw = jdbcClient
                .sql("""
                    INSERT INTO building_register_raw_page
                      (endpoint_snapshot_id,request_id,page_no,attempt_no,status,response_body,body_sha256,byte_count,http_status,finalized_at)
                    SELECT endpoint_snapshot_id,gen_random_uuid(),2,1,'PARSED',:body,repeat('b',64),octet_length(:body),200,now()
                    FROM building_register_raw_page WHERE id=:raw
                    RETURNING id
                    """)
                .param("body", """
                        {"response":{"header":{"resultCode":"00"},"body":{"pageNo":2,"numOfRows":100,
                        "totalCount":1,"items":{"item":{"platArea":"not-a-number"}}}}}
                        """)
                .param("raw", rawPageId)
                .query(Long.class)
                .single();
        UUID parseV3 = UUID.fromString("123e4567-e89b-12d3-a456-426614174223");
        assertThat(service.replay(new BuildingProfileReplayCommand(COLLECTION_ID, parseV3, "PROFILE_V3", 10))
                        .completed())
                .isTrue();
        assertThat(jdbcClient
                        .sql("""
                            SELECT observation_type FROM building_register_profile_schema_observation
                            WHERE parse_run_id=:run AND raw_page_id=:raw ORDER BY observation_type
                            """)
                        .param("run", parseV3)
                        .param("raw", driftRaw)
                        .query(String.class)
                        .list())
                .containsExactly("MISSING_REQUIRED", "TYPE_DRIFT");

        UUID parseV4 = UUID.fromString("123e4567-e89b-12d3-a456-426614174224");
        BuildingProfileReplayCommand failureRun =
                new BuildingProfileReplayCommand(COLLECTION_ID, parseV4, "PROFILE_V4", 10);
        repository.startOrResume(failureRun);
        repository.recordFailure(
                parseV4,
                new BuildingProfileRawPage(
                        driftRaw,
                        BuildingRegisterEndpoint.TITLE,
                        "1168010300101400001",
                        2,
                        100,
                        BuildingRegisterRawPageStatus.PARSED,
                        "99",
                        "{}"),
                BuildingProfileParseStatus.PARSE_FAILED,
                "unsafe provider failure !" + "x".repeat(100));
        assertThat(jdbcClient
                        .sql("""
                            SELECT status,failure_reason FROM building_register_profile_parse_page
                            WHERE parse_run_id=:run AND raw_page_id=:raw
                            """)
                        .param("run", parseV4)
                        .param("raw", driftRaw)
                        .query((rs, rowNum) -> List.of(rs.getString("status"), rs.getString("failure_reason")))
                        .single())
                .satisfies(failure -> {
                    assertThat(failure.get(0)).isEqualTo("PARSE_FAILED");
                    assertThat(failure.get(1)).hasSize(80).doesNotContain(" ", "!");
                });
        assertThatThrownBy(() -> repository.recordFailure(
                        parseV4,
                        new BuildingProfileRawPage(
                                driftRaw,
                                BuildingRegisterEndpoint.TITLE,
                                "1168010300101400001",
                                2,
                                100,
                                BuildingRegisterRawPageStatus.PARSED,
                                null,
                                "{}"),
                        BuildingProfileParseStatus.PARSED,
                        "not-a-failure"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
