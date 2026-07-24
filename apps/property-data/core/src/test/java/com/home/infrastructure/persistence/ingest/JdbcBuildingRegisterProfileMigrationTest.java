package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class JdbcBuildingRegisterProfileMigrationTest extends JdbcMigrationTestSupport {
    private static final UUID COLLECTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174200");
    private static final UUID PARSE_RUN_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174201");

    @Test
    @DisplayName("profile discovery schema는 versioned typed evidence를 append-only로 보존한다")
    void createsVersionedProfileEvidenceSchema() {
        flyway(null).clean();
        flyway(null).migrate();

        assertThat(List.of(
                        "building_register_profile_sample_stratum",
                        "building_register_profile_sample_pnu",
                        "building_register_profile_parse_run",
                        "building_register_profile_parse_page",
                        "building_register_profile_record",
                        "building_register_profile_value",
                        "building_register_profile_schema_observation",
                        "building_register_profile_hierarchy_reason",
                        "building_register_profile_scope_assignment",
                        "building_register_profile_complex_match",
                        "legal_dong_code_import",
                        "legal_dong_code_mapping",
                        "building_register_profile_code_lookup",
                        "building_register_profile_analysis_run",
                        "building_register_profile_comparison",
                        "building_register_profile_field_quality"))
                .allMatch(table -> regclass(table) != null);
    }

    @Test
    @DisplayName("profile evidence는 runtime role에 삭제 없는 최소 권한만 부여한다")
    void grantsAppendOnlyRuntimePrivileges() {
        flyway(null).clean();
        flyway(null).migrate();

        List<String> tables = List.of(
                "building_register_profile_sample_stratum",
                "building_register_profile_sample_pnu",
                "building_register_profile_parse_run",
                "building_register_profile_parse_page",
                "building_register_profile_record",
                "building_register_profile_value",
                "building_register_profile_schema_observation",
                "building_register_profile_hierarchy_reason",
                "building_register_profile_scope_assignment",
                "building_register_profile_complex_match",
                "legal_dong_code_import",
                "legal_dong_code_mapping",
                "building_register_profile_code_lookup",
                "building_register_profile_analysis_run",
                "building_register_profile_comparison",
                "building_register_profile_field_quality");
        assertThat(tables).allSatisfy(table -> {
            assertThat(hasTablePrivilege(table, "SELECT,INSERT,UPDATE")).isTrue();
            assertThat(hasTablePrivilege(table, "DELETE")).isFalse();
        });
    }

    @Test
    @DisplayName("같은 raw는 parser version별 record를 보존하고 같은 run의 duplicate item을 거절한다")
    void versionsTypedRecordsByParseRunRawPageAndItem() {
        Seed seed = seedRawPage();
        insertParseRun(PARSE_RUN_ID, "PROFILE_V1");
        insertParsePage(PARSE_RUN_ID, seed.rawPageId());
        long recordId = insertRecord(PARSE_RUN_ID, seed.rawPageId(), 0);
        insertValue(recordId, "PLAT_AREA", "DECIMAL", "POSITIVE", "100.25");

        assertThatThrownBy(() -> insertRecord(PARSE_RUN_ID, seed.rawPageId(), 0))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID secondRun = UUID.fromString("123e4567-e89b-12d3-a456-426614174202");
        insertParseRun(secondRun, "PROFILE_V2");
        insertParsePage(secondRun, seed.rawPageId());
        insertRecord(secondRun, seed.rawPageId(), 0);
        assertThat(jdbcClient
                        .sql("SELECT count(*) FROM building_register_profile_record")
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("profile campaign은 고정 seed/sample 설정을 요구하고 기존 ratio 의미를 유지한다")
    void requiresFrozenProfileCampaignConfiguration() {
        flyway(null).clean();
        flyway(null).migrate();

        jdbcClient.sql("""
                    INSERT INTO building_register_collection_campaign
                      (collection_id,mode,strategy,to_complex_id,status,purpose,target_scope,selection_seed,sample_size)
                    VALUES (:id,'profile','COMPARE_RECAP_TITLE',1,'CREATED',
                            'PROFILE_DISCOVERY','VALIDATION_SAMPLE','seed-v1',1500)
                    """).param("id", COLLECTION_ID).update();

        assertThatThrownBy(
                        () -> jdbcClient.sql("""
                            INSERT INTO building_register_collection_campaign
                              (collection_id,mode,strategy,to_complex_id,status,purpose,target_scope,sample_size)
                            VALUES (:id,'profile','COMPARE_RECAP_TITLE',1,'CREATED',
                                    'PROFILE_DISCOVERY','VALIDATION_SAMPLE',1500)
                            """).param("id", UUID.randomUUID()).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcClient.sql("""
                    INSERT INTO building_register_collection_campaign
                      (collection_id,mode,strategy,to_complex_id,status,purpose,target_scope,selection_seed,sample_size)
                    VALUES (:id,'profile','COMPARE_RECAP_TITLE',1,'CREATED',
                            'PROFILE_DISCOVERY','NATIONWIDE_STAGING','nationwide-v1',43721)
                    """).param("id", UUID.randomUUID()).update();

        assertThatThrownBy(
                        () -> jdbcClient.sql("""
                            INSERT INTO building_register_collection_campaign
                              (collection_id,mode,strategy,to_complex_id,status,purpose,target_scope,selection_seed,sample_size)
                            VALUES (:id,'profile','COMPARE_RECAP_TITLE',1,'CREATED',
                                    'PROFILE_DISCOVERY','UNKNOWN_SCOPE','seed',1)
                            """).param("id", UUID.randomUUID()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Seed seedRawPage() {
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
                      (collection_id,mode,strategy,to_complex_id,status)
                    VALUES (:id,'missing','ADAPTIVE',1000,'COLLECTING')
                    """).param("id", COLLECTION_ID).update();
        long snapshotId =
                jdbcClient.sql("""
                    INSERT INTO building_register_endpoint_snapshot
                      (collection_id,pnu,endpoint,run_date,page_size,attempt_no,status,total_count,completed_at)
                    VALUES (:id,'1168010300101400001','TITLE',DATE '2026-07-20',100,1,'PARSED',1,now())
                    RETURNING id
                    """).param("id", COLLECTION_ID).query(Long.class).single();
        long rawPageId = jdbcClient
                .sql("""
                    INSERT INTO building_register_raw_page
                      (endpoint_snapshot_id,request_id,page_no,attempt_no,status,response_body,body_sha256,byte_count,http_status,finalized_at)
                    VALUES (:snapshot,gen_random_uuid(),1,1,'PARSED','{}',repeat('a',64),2,200,now())
                    RETURNING id
                    """)
                .param("snapshot", snapshotId)
                .query(Long.class)
                .single();
        return new Seed(rawPageId);
    }

    private void insertParseRun(UUID runId, String parserVersion) {
        jdbcClient
                .sql("""
                    INSERT INTO building_register_profile_parse_run
                      (parse_run_id,source_collection_id,parser_version,status)
                    VALUES (:run,:collection,:version,'RUNNING')
                    """)
                .param("run", runId)
                .param("collection", COLLECTION_ID)
                .param("version", parserVersion)
                .update();
    }

    private long insertRecord(UUID runId, long rawPageId, int itemIndex) {
        return jdbcClient
                .sql("""
                    INSERT INTO building_register_profile_record
                      (parse_run_id,raw_page_id,item_index,pnu,endpoint)
                    VALUES (:run,:raw,:item,'1168010300101400001','TITLE')
                    RETURNING id
                    """)
                .param("run", runId)
                .param("raw", rawPageId)
                .param("item", itemIndex)
                .query(Long.class)
                .single();
    }

    private void insertParsePage(UUID runId, long rawPageId) {
        jdbcClient.sql("""
                    INSERT INTO building_register_profile_parse_page
                      (parse_run_id,raw_page_id,status,total_count,record_count)
                    VALUES (:run,:raw,'PARSED',1,1)
                    """).param("run", runId).param("raw", rawPageId).update();
    }

    private void insertValue(long recordId, String field, String type, String state, String value) {
        jdbcClient
                .sql("""
                    INSERT INTO building_register_profile_value
                      (profile_record_id,field_id,value_type,value_state,raw_value,decimal_value)
                    VALUES (:record,:field,:type,:state,:raw,CAST(:raw AS numeric))
                    """)
                .param("record", recordId)
                .param("field", field)
                .param("type", type)
                .param("state", state)
                .param("raw", value)
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

    private boolean hasTablePrivilege(String table, String privileges) {
        return jdbcClient
                .sql("SELECT has_table_privilege(:role,:table,:privileges)")
                .param("role", PROPERTY_RUNTIME_ROLE)
                .param("table", table)
                .param("privileges", privileges)
                .query(Boolean.class)
                .single();
    }

    private record Seed(long rawPageId) {}
}
