package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.ingest.buildingprofile.BuildingProfileProjectionCommand;
import com.home.domain.complex.buildingprofile.BuildingProfileField;
import com.home.domain.complex.buildingprofile.BuildingProfileProjectionPolicy;
import com.home.infrastructure.persistence.ingest.matching.JdbcBuildingProfileProjectionRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcBuildingProfileProjectionRepositoryTest extends JdbcMigrationTestSupport {
    private static final UUID COLLECTION = UUID.fromString("123e4567-e89b-12d3-a456-426614174300");
    private static final UUID PARSE = UUID.fromString("123e4567-e89b-12d3-a456-426614174301");
    private static final UUID ANALYSIS = UUID.fromString("123e4567-e89b-12d3-a456-426614174302");
    private static final UUID PROJECTION = UUID.fromString("123e4567-e89b-12d3-a456-426614174303");
    private static final String PNU = "1168010300101400001";
    private long rootRecord;
    private long titleRecord;

    @BeforeEach
    void setUp() {
        flyway(null).clean();
        flyway(null).migrate();
        jdbcClient
                .sql("INSERT INTO parcel(id,pnu,latitude,longitude) VALUES (9201,:pnu,37.5,127.0)")
                .param("pnu", PNU)
                .update();
        jdbcClient.sql("""
                    INSERT INTO complex(id,parcel_id,complex_pk,apt_seq,name,trade_name,unit_cnt)
                    VALUES (9202,9201,'P:2','P-2','before','before',777)
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO building_register_collection_campaign
                      (collection_id,mode,strategy,to_complex_id,status,purpose,target_scope,
                       selection_seed,sample_size,completed_at)
                    VALUES (:collection,'profile','COMPARE_RECAP_TITLE',9202,'COMPLETED','PROFILE_DISCOVERY',
                            'VALIDATION_SAMPLE','seed',1,now())
                    """).param("collection", COLLECTION).update();
        jdbcClient.sql("""
                    INSERT INTO building_register_collection_target(collection_id,target_ordinal,complex_id,pnu)
                    VALUES (:collection,1,9202,:pnu)
                    """).param("collection", COLLECTION).param("pnu", PNU).update();
        long snapshot = jdbcClient
                .sql("""
                    INSERT INTO building_register_endpoint_snapshot
                      (collection_id,pnu,endpoint,run_date,page_size,attempt_no,status,total_count,completed_at)
                    VALUES (:collection,:pnu,'RECAP_TITLE',DATE '2026-07-21',100,1,'PARSED',2,now())
                    RETURNING id
                    """)
                .param("collection", COLLECTION)
                .param("pnu", PNU)
                .query(Long.class)
                .single();
        long raw = jdbcClient
                .sql("""
                    INSERT INTO building_register_raw_page
                      (endpoint_snapshot_id,request_id,page_no,attempt_no,status,response_body,
                       body_sha256,byte_count,http_status,finalized_at)
                    VALUES (:snapshot,gen_random_uuid(),1,1,'PARSED','{}',repeat('a',64),2,200,now())
                    RETURNING id
                    """)
                .param("snapshot", snapshot)
                .query(Long.class)
                .single();
        jdbcClient
                .sql("""
                    INSERT INTO building_register_profile_parse_run
                      (parse_run_id,source_collection_id,parser_version,status,page_count,record_count,completed_at)
                    VALUES (:parse,:collection,'PROFILE_V2','COMPLETED',1,2,now())
                    """)
                .param("parse", PARSE)
                .param("collection", COLLECTION)
                .update();
        jdbcClient.sql("""
                    INSERT INTO building_register_profile_parse_page
                      (parse_run_id,raw_page_id,status,record_count) VALUES (:parse,:raw,'PARSED',2)
                    """).param("parse", PARSE).param("raw", raw).update();
        rootRecord = record(raw, 0, "RECAP_TITLE", "ROOT", null, "1", "b".repeat(64));
        titleRecord = record(raw, 1, "TITLE", "TITLE-1", "ROOT", "2", "c".repeat(64));
        decimalValue(rootRecord, "PLAT_AREA", "SITE", "100.5");
        integerValue(rootRecord, "HHLD_CNT", "SITE", 321);
        dateValue(rootRecord, "USE_APR_DAY", "SITE", "2020-01-02");
        textValue(titleRecord, "MAIN_PURPS_CD", "BUILDING", "02000");
        integerValue(titleRecord, "INDR_AUTO_UTCNT", "BUILDING", 42);

        jdbcClient
                .sql("""
                    INSERT INTO building_register_profile_analysis_run
                      (analysis_run_id,collection_id,parse_run_id,rules_version,status,completed_at)
                    VALUES (:analysis,:collection,:parse,'PROFILE_V1','COMPLETED',now())
                    """)
                .param("analysis", ANALYSIS)
                .param("collection", COLLECTION)
                .param("parse", PARSE)
                .update();
        assignment(rootRecord, "ROOT");
        assignment(titleRecord, "ROOT");
        jdbcClient
                .sql("""
                    INSERT INTO building_register_profile_complex_match
                      (analysis_run_id,collection_id,complex_id,pnu,scope_key,status,projectable)
                    VALUES (:analysis,:collection,9202,:pnu,'ROOT','RESOLVED',true)
                    """)
                .param("analysis", ANALYSIS)
                .param("collection", COLLECTION)
                .param("pnu", PNU)
                .update();

        BuildingProfileProjectionPolicy policy = new BuildingProfileProjectionPolicy();
        for (BuildingProfileField field : policy.fields()) {
            String tier = field == BuildingProfileField.STCNS_DAY || field == BuildingProfileField.USE_APR_DAY
                    ? "REJECT_FOR_PROJECTION"
                    : field == BuildingProfileField.PLAT_AREA ? "RETAIN_PROFILE" : "PROMOTE_CANDIDATE";
            jdbcClient
                    .sql("""
                        INSERT INTO building_register_profile_field_quality
                          (analysis_run_id,field_id,field_scope,stratum,source_record_coverage,building_coverage,
                           pnu_coverage,projectable_complex_readiness,operational_completion,invalid_rate,
                           conflict_rate,quality_tier,meaning_verified,numerator,denominator)
                        VALUES (:analysis,:field,:scope,'WEIGHTED_NATIONAL',1,1,1,:readiness,1,0,0,:tier,true,1,1)
                        """)
                    .param("analysis", ANALYSIS)
                    .param("field", field.name())
                    .param("scope", field.scope().name())
                    .param("readiness", policy.minimumReadiness())
                    .param("tier", tier)
                    .update();
        }
    }

    @Test
    @DisplayName("55개 필드를 complex 변경 없이 idempotent하게 projection한다")
    void projectsFiftyFiveFieldsIdempotentlyWithoutChangingComplex() {
        JdbcBuildingProfileProjectionRepository repository =
                new JdbcBuildingProfileProjectionRepository(jdbcClient, transactionTemplate);
        var command = new BuildingProfileProjectionCommand(PROJECTION, ANALYSIS, "PROFILE_PROJECTION_V1");

        var first = repository.project(command, new BuildingProfileProjectionPolicy());
        var second = repository.project(command, new BuildingProfileProjectionPolicy());

        assertThat(first.eligibleFieldCount()).isEqualTo(55);
        assertThat(first.complexCount()).isOne();
        assertThat(first.projectableComplexCount()).isOne();
        assertThat(first.buildingCount()).isOne();
        assertThat(first.complexSnapshotSha256()).hasSize(64);
        assertThat(first.alreadyCompleted()).isFalse();
        assertThat(second.alreadyCompleted()).isTrue();
        assertThat(jdbcClient
                        .sql("""
                    SELECT row(plat_area,hhld_cnt,use_apr_day)::text
                    FROM complex_building_register_profile
                    WHERE projection_run_id=:projection AND complex_id=9202
                    """)
                        .param("projection", PROJECTION)
                        .query(String.class)
                        .single())
                .isEqualTo("(100.500000000000,321,2020-01-02)");
        assertThat(jdbcClient
                        .sql("""
                    SELECT row(main_purps_cd,indr_auto_utcnt)::text
                    FROM complex_building_register_building
                    WHERE projection_run_id=:projection AND complex_id=9202
                    """)
                        .param("projection", PROJECTION)
                        .query(String.class)
                        .single())
                .isEqualTo("(02000,42)");
        assertThat(jdbcClient.sql("""
                    SELECT row(name,unit_cnt)::text FROM complex WHERE id=9202
                    """).query(String.class).single()).isEqualTo("(before,777)");
        assertThat(jdbcClient
                        .sql("""
                    SELECT count(*) FROM building_register_profile_projected_quality
                    WHERE projection_run_id=:projection
                    """)
                        .param("projection", PROJECTION)
                        .query(Integer.class)
                        .single())
                .isEqualTo(55);
        assertThat(jdbcClient
                        .sql("""
                    SELECT projection_use FROM building_register_profile_projected_quality
                    WHERE projection_run_id=:projection AND field_id='USE_APR_DAY'
                    """)
                        .param("projection", PROJECTION)
                        .query(String.class)
                        .single())
                .isEqualTo("OBSERVATION_ONLY");
    }

    private long record(long raw, int index, String endpoint, String key, String parent, String kind, String hash) {
        return jdbcClient
                .sql("""
                    INSERT INTO building_register_profile_record
                      (parse_run_id,raw_page_id,item_index,pnu,endpoint,mgm_bldrgst_pk,
                       mgm_up_bldrgst_pk,regstr_kind_cd,content_sha256)
                    VALUES (:parse,:raw,:index,:pnu,:endpoint,:key,:parent,:kind,:hash) RETURNING id
                    """)
                .param("parse", PARSE)
                .param("raw", raw)
                .param("index", index)
                .param("pnu", PNU)
                .param("endpoint", endpoint)
                .param("key", key)
                .param("parent", parent)
                .param("kind", kind)
                .param("hash", hash)
                .query(Long.class)
                .single();
    }

    private void assignment(long record, String scope) {
        jdbcClient
                .sql("""
                    INSERT INTO building_register_profile_scope_assignment
                      (analysis_run_id,profile_record_id,root_management_key,scope_key,status)
                    VALUES (:analysis,:record,'ROOT',:scope,'RESOLVED')
                    """)
                .param("analysis", ANALYSIS)
                .param("record", record)
                .param("scope", scope)
                .update();
    }

    private void textValue(long record, String field, String scope, String value) {
        value(record, field, scope, "TEXT", "VALID", "text_value", value);
    }

    private void decimalValue(long record, String field, String scope, String value) {
        value(record, field, scope, "DECIMAL", "POSITIVE", "decimal_value", new java.math.BigDecimal(value));
    }

    private void integerValue(long record, String field, String scope, long value) {
        value(record, field, scope, "INTEGER", "POSITIVE", "integer_value", value);
    }

    private void dateValue(long record, String field, String scope, String value) {
        jdbcClient
                .sql("""
                    INSERT INTO building_register_profile_value
                      (profile_record_id,field_id,field_scope,value_type,value_state,raw_value,date_value)
                    VALUES (:record,:field,:scope,'DATE','VALID',:value,CAST(:value AS date))
                    """)
                .param("record", record)
                .param("field", field)
                .param("scope", scope)
                .param("value", value)
                .update();
    }

    private void value(
            long record, String field, String scope, String type, String state, String column, Object value) {
        jdbcClient
                .sql("""
                    INSERT INTO building_register_profile_value
                      (profile_record_id,field_id,field_scope,value_type,value_state,raw_value,%s)
                    VALUES (:record,:field,:scope,:type,:state,:raw,:value)
                    """.formatted(column))
                .param("record", record)
                .param("field", field)
                .param("scope", scope)
                .param("type", type)
                .param("state", state)
                .param("raw", value.toString())
                .param("value", value)
                .update();
    }
}
