package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.ingest.buildingprofile.BuildingProfilePublicationCommand;
import com.home.domain.complex.buildingprofile.BuildingProfileField;
import com.home.infrastructure.persistence.ingest.matching.JdbcBuildingProfilePublicationRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcBuildingProfilePublicationRepositoryTest extends JdbcMigrationTestSupport {
    private static final UUID COLLECTION = UUID.fromString("223e4567-e89b-12d3-a456-426614174300");
    private static final UUID PARSE = UUID.fromString("223e4567-e89b-12d3-a456-426614174301");
    private static final UUID ANALYSIS = UUID.fromString("223e4567-e89b-12d3-a456-426614174302");
    private static final UUID PROJECTION = UUID.fromString("223e4567-e89b-12d3-a456-426614174303");
    private static final UUID PUBLICATION = UUID.fromString("223e4567-e89b-12d3-a456-426614174304");
    private static final String PNU = "1168010300101400001";
    private long rawPage;
    private long rootRecord;
    private long titleRecord;

    @BeforeEach
    void setUp() {
        flyway(null).clean();
        flyway(null).migrate();
        seedLineage();
    }

    @Test
    @DisplayName("완전한 83-field projection을 idempotent publication으로 발행한다")
    void publishesCompleteProfileIdempotently() {
        JdbcBuildingProfilePublicationRepository repository =
                new JdbcBuildingProfilePublicationRepository(jdbcClient, transactionTemplate, dataSource);
        var command =
                new BuildingProfilePublicationCommand(PUBLICATION, PROJECTION, "PROFILE_PUBLICATION_V1", true, true);

        var first = repository.publish(command);
        var second = repository.publish(command);

        assertThat(first.alreadyCompleted()).isFalse();
        assertThat(second.alreadyCompleted()).isTrue();
        assertThat(first.siteCount()).isOne();
        assertThat(first.buildingCount()).isOne();
        assertThat(first.hierarchyCount()).isEqualTo(2);
        assertThat(first.evidenceCount()).isEqualTo(166);
        assertThat(first.summaryCount()).isOne();
        assertThat(first.contentSha256()).hasSize(64);
        assertThat(publicationStatus()).isEqualTo("PUBLISHED");
        assertThat(jdbcClient
                        .sql("""
                    SELECT row(hhld_cnt,bc_rat)::text
                    FROM building_register_profile_site
                    WHERE publication_id=:publication
                    """)
                        .param("publication", PUBLICATION)
                        .query(String.class)
                        .single())
                .isEqualTo("(321,25.00000000)");
        assertThat(jdbcClient
                        .sql("""
                    SELECT row(ho_cnt,grnd_flr_cnt)::text
                    FROM building_register_profile_building
                    WHERE publication_id=:publication
                    """)
                        .param("publication", PUBLICATION)
                        .query(String.class)
                        .single())
                .isEqualTo("(42,20)");
        assertThat(jdbcClient
                        .sql("""
                    SELECT row(household_count,unit_count,max_ground_floor_count,
                               floor_area_ratio,total_floor_area_m2,safety_quality,
                               seismic_design_status,parking_scope,parking_quality,total_parking_count)::text
                    FROM complex_building_register_profile_summary
                    WHERE publication_id=:publication AND complex_id=9202
                    """)
                        .param("publication", PUBLICATION)
                        .query(String.class)
                        .single())
                .isEqualTo("(321,42,20,20.00000000,5000.000000000000,PARTIAL,UNKNOWN,COMPLEX,PARTIAL,)");
        assertThat(jdbcClient
                        .sql("SELECT unit_cnt FROM complex WHERE id=9202")
                        .query(Long.class)
                        .single())
                .isEqualTo(777L);
        assertThat(jdbcClient
                        .sql("""
                    SELECT conflict_status FROM building_register_profile_field_evidence
                    WHERE publication_id=:publication AND source_method='PNU_ROOT' AND field_id='BC_RAT'
                      AND value_state='POSITIVE'
                    """)
                        .param("publication", PUBLICATION)
                        .query(String.class)
                        .single())
                .isEqualTo("AGGREGATE_CONFLICT");
        assertThat(jdbcClient
                        .sql("""
                    SELECT conflict_status FROM building_register_profile_field_evidence
                    WHERE publication_id=:publication AND source_method='PNU_ROOT'
                      AND field_id='TOT_PKNG_CNT' AND value_state='POSITIVE'
                    """)
                        .param("publication", PUBLICATION)
                        .query(String.class)
                        .single())
                .isEqualTo("AGGREGATE_CONFLICT");
    }

    @Test
    @DisplayName("83-field source value가 하나라도 빠지면 기존 publication을 유지한다")
    void rejectsIncompleteFieldSetWithoutReplacingPublishedProfile() {
        jdbcClient.sql("""
                    DELETE FROM building_register_profile_value
                    WHERE profile_record_id=:record AND field_id='ENGR_EPI'
                    """).param("record", titleRecord).update();
        jdbcClient.sql("""
                    INSERT INTO building_register_profile_value(
                      profile_record_id,field_id,field_scope,aggregation_method,zero_policy,
                      value_type,value_state)
                    VALUES (:record,'UNKNOWN_REPLACEMENT','BUILDING','SET','VALID','TEXT','ABSENT')
                    """).param("record", titleRecord).update();
        JdbcBuildingProfilePublicationRepository repository =
                new JdbcBuildingProfilePublicationRepository(jdbcClient, transactionTemplate, dataSource);

        assertThatThrownBy(() -> repository.publish(new BuildingProfilePublicationCommand(
                        PUBLICATION, PROJECTION, "PROFILE_PUBLICATION_V1", true, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("83-field");

        assertThat(jdbcClient
                        .sql("SELECT count(*) FROM building_register_profile_publication")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("shared PNU는 root consensus만 PARCEL fallback으로 공개한다")
    void publishesSharedPnuConsensusAndSuppressesConflict() {
        addSharedComplex(9203);
        JdbcBuildingProfilePublicationRepository repository =
                new JdbcBuildingProfilePublicationRepository(jdbcClient, transactionTemplate, dataSource);

        repository.publish(
                new BuildingProfilePublicationCommand(PUBLICATION, PROJECTION, "PROFILE_PUBLICATION_V1", true, false));

        assertThat(jdbcClient
                        .sql("""
                    SELECT row(ratio_scope,ratio_quality,building_coverage_rate)::text
                    FROM complex_building_register_profile_summary
                    WHERE publication_id=:publication AND complex_id=9203
                    """)
                        .param("publication", PUBLICATION)
                        .query(String.class)
                        .single())
                .isEqualTo("(PARCEL,PNU_FALLBACK,25.00000000)");

        flyway(null).clean();
        flyway(null).migrate();
        seedLineage();
        addSharedComplex(9203);
        long conflictingRoot = record(rawPage, 2, "RECAP_TITLE", "ROOT-2", null, "1");
        completeValues(conflictingRoot);
        value(conflictingRoot, BuildingProfileField.BC_RAT, new java.math.BigDecimal("35"));
        value(conflictingRoot, BuildingProfileField.PLAT_AREA, new java.math.BigDecimal("1000"));

        repository = new JdbcBuildingProfilePublicationRepository(jdbcClient, transactionTemplate, dataSource);
        repository.publish(
                new BuildingProfilePublicationCommand(PUBLICATION, PROJECTION, "PROFILE_PUBLICATION_V1", true, false));

        assertThat(jdbcClient
                        .sql("""
                    SELECT building_coverage_rate
                    FROM complex_building_register_profile_summary
                    WHERE publication_id=:publication AND complex_id=9203
                    """)
                        .param("publication", PUBLICATION)
                        .query(java.math.BigDecimal.class)
                        .optional())
                .isEmpty();
        assertThat(jdbcClient
                        .sql("""
                    SELECT count(*) FROM building_register_profile_field_evidence
                    WHERE publication_id=:publication AND field_id='BC_RAT'
                      AND conflict_status='SOURCE_CONFLICT'
                    """)
                        .param("publication", PUBLICATION)
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
    }

    private void seedLineage() {
        jdbcClient
                .sql("INSERT INTO parcel(id,pnu,address) VALUES (9201,:pnu,'서울 표본구 1')")
                .param("pnu", PNU)
                .update();
        jdbcClient.sql("""
                    INSERT INTO complex(id,parcel_id,complex_pk,apt_seq,name,unit_cnt)
                    VALUES (9202,9201,'P:2','P-2','표본',777)
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO building_register_collection_campaign
                      (collection_id,mode,strategy,to_complex_id,status,purpose,target_scope,
                       selection_seed,sample_size,completed_at)
                    VALUES (:collection,'profile','COMPARE_RECAP_TITLE',9202,'COMPLETED','PROFILE_DISCOVERY',
                            'NATIONWIDE_STAGING','publication-test',1,now())
                    """).param("collection", COLLECTION).update();
        rawPage = rawPage();
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
                    INSERT INTO building_register_profile_parse_page(parse_run_id,raw_page_id,status,record_count)
                    VALUES (:parse,:raw,'PARSED',2)
                    """).param("parse", PARSE).param("raw", rawPage).update();
        rootRecord = record(rawPage, 0, "RECAP_TITLE", "ROOT", null, "1");
        titleRecord = record(rawPage, 1, "TITLE", "TITLE-1", "ROOT", "2");
        completeValues(rootRecord);
        completeValues(titleRecord);
        value(rootRecord, BuildingProfileField.HHLD_CNT, 321L);
        value(rootRecord, BuildingProfileField.BC_RAT, new java.math.BigDecimal("25"));
        value(rootRecord, BuildingProfileField.PLAT_AREA, new java.math.BigDecimal("1000"));
        value(rootRecord, BuildingProfileField.TOT_DONG_TOT_AREA, new java.math.BigDecimal("5000"));
        value(rootRecord, BuildingProfileField.TOT_PKNG_CNT, 10L);
        value(titleRecord, BuildingProfileField.HO_CNT, 42L);
        value(titleRecord, BuildingProfileField.GRND_FLR_CNT, 20L);
        value(titleRecord, BuildingProfileField.ARCH_AREA, new java.math.BigDecimal("100"));
        value(titleRecord, BuildingProfileField.TOT_AREA, new java.math.BigDecimal("3000"));
        value(titleRecord, BuildingProfileField.VL_RAT_ESTM_TOT_AREA, new java.math.BigDecimal("200"));
        value(titleRecord, BuildingProfileField.INDR_MECH_UTCNT, 3L);
        value(titleRecord, BuildingProfileField.OUDR_MECH_UTCNT, 3L);
        value(titleRecord, BuildingProfileField.INDR_AUTO_UTCNT, 3L);
        value(titleRecord, BuildingProfileField.OUDR_AUTO_UTCNT, 3L);

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
        assignment(rootRecord);
        assignment(titleRecord);
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
        jdbcClient
                .sql("""
                    INSERT INTO building_register_profile_projection_run
                      (projection_run_id,analysis_run_id,collection_id,parse_run_id,projection_version,
                       minimum_readiness,status,complex_snapshot_sha256,eligible_field_count,complex_count,
                       projectable_complex_count,building_count,completed_at)
                    VALUES (:projection,:analysis,:collection,:parse,'PROFILE_PROJECTION_V1',0.5,'COMPLETED',
                            repeat('a',64),55,1,1,1,now())
                    """)
                .param("projection", PROJECTION)
                .param("analysis", ANALYSIS)
                .param("collection", COLLECTION)
                .param("parse", PARSE)
                .update();
        jdbcClient
                .sql("""
                    INSERT INTO complex_building_register_profile
                      (projection_run_id,complex_id,analysis_run_id,collection_id,assignment_status,
                       projectable,source_scope_key,source_root_management_key,hhld_cnt,plat_area)
                    VALUES (:projection,9202,:analysis,:collection,'RESOLVED',true,'ROOT','ROOT',321,1000)
                    """)
                .param("projection", PROJECTION)
                .param("analysis", ANALYSIS)
                .param("collection", COLLECTION)
                .update();
        jdbcClient.sql("""
                    INSERT INTO complex_building_register_building
                      (projection_run_id,complex_id,source_management_key,source_parent_management_key,
                       ho_cnt,grnd_flr_cnt)
                    VALUES (:projection,9202,'TITLE-1','ROOT',42,20)
                    """).param("projection", PROJECTION).update();
    }

    private long rawPage() {
        long snapshot = jdbcClient
                .sql("""
                    INSERT INTO building_register_endpoint_snapshot
                      (collection_id,pnu,endpoint,run_date,page_size,attempt_no,status,total_count,completed_at)
                    VALUES (:collection,:pnu,'RECAP_TITLE',DATE '2026-07-27',100,1,'PARSED',2,now()) RETURNING id
                    """)
                .param("collection", COLLECTION)
                .param("pnu", PNU)
                .query(Long.class)
                .single();
        return jdbcClient.sql("""
                    INSERT INTO building_register_raw_page
                      (endpoint_snapshot_id,request_id,page_no,attempt_no,status,response_body,
                       body_sha256,byte_count,http_status,finalized_at)
                    VALUES (:snapshot,gen_random_uuid(),1,1,'PARSED','{}',repeat('a',64),2,200,now()) RETURNING id
                    """).param("snapshot", snapshot).query(Long.class).single();
    }

    private long record(long raw, int index, String endpoint, String key, String parent, String kind) {
        return jdbcClient
                .sql("""
                    INSERT INTO building_register_profile_record
                      (parse_run_id,raw_page_id,item_index,pnu,endpoint,mgm_bldrgst_pk,mgm_up_bldrgst_pk,
                       regstr_kind_cd,content_sha256)
                    VALUES (:parse,:raw,:item,:pnu,:endpoint,:key,:parent,:kind,repeat(:hash,64)) RETURNING id
                    """)
                .param("parse", PARSE)
                .param("raw", raw)
                .param("item", index)
                .param("pnu", PNU)
                .param("endpoint", endpoint)
                .param("key", key)
                .param("parent", parent)
                .param("kind", kind)
                .param("hash", index == 0 ? "b" : "c")
                .query(Long.class)
                .single();
    }

    private void completeValues(long record) {
        for (BuildingProfileField field : BuildingProfileField.values()) {
            jdbcClient
                    .sql("""
                        INSERT INTO building_register_profile_value
                          (profile_record_id,field_id,field_scope,aggregation_method,zero_policy,
                           value_type,value_state)
                        VALUES (:record,:field,:scope,:aggregation,:zero,:type,'ABSENT')
                        """)
                    .param("record", record)
                    .param("field", field.name())
                    .param("scope", field.scope().name())
                    .param("aggregation", field.aggregation().name())
                    .param("zero", field.zeroPolicy().name())
                    .param("type", field.valueType().name())
                    .update();
        }
    }

    private void value(long record, BuildingProfileField field, Object value) {
        String column = value instanceof Long ? "integer_value" : "decimal_value";
        String state = value instanceof Long ? "POSITIVE" : "POSITIVE";
        jdbcClient
                .sql("UPDATE building_register_profile_value SET value_state=:state," + column
                        + "=:value WHERE profile_record_id=:record AND field_id=:field")
                .param("state", state)
                .param("value", value)
                .param("record", record)
                .param("field", field.name())
                .update();
    }

    private void assignment(long record) {
        jdbcClient.sql("""
                    INSERT INTO building_register_profile_scope_assignment
                      (analysis_run_id,profile_record_id,root_management_key,scope_key,status)
                    VALUES (:analysis,:record,'ROOT','ROOT','RESOLVED')
                    """).param("analysis", ANALYSIS).param("record", record).update();
    }

    private void addSharedComplex(long complexId) {
        jdbcClient
                .sql("""
                    INSERT INTO complex(id,parcel_id,complex_pk,apt_seq,name)
                    VALUES (:complex,9201,:key,:apt,'공유 표본')
                    """)
                .param("complex", complexId)
                .param("key", "P:" + complexId)
                .param("apt", "P-" + complexId)
                .update();
        jdbcClient
                .sql("""
                    INSERT INTO building_register_profile_complex_match
                      (analysis_run_id,collection_id,complex_id,pnu,scope_key,status,projectable)
                    VALUES (:analysis,:collection,:complex,:pnu,'ROOT','SHARED_SCOPE',false)
                    """)
                .param("analysis", ANALYSIS)
                .param("collection", COLLECTION)
                .param("complex", complexId)
                .param("pnu", PNU)
                .update();
        jdbcClient
                .sql("""
                    INSERT INTO complex_building_register_profile
                      (projection_run_id,complex_id,analysis_run_id,collection_id,assignment_status,projectable)
                    VALUES (:projection,:complex,:analysis,:collection,'SHARED_SCOPE',false)
                    """)
                .param("projection", PROJECTION)
                .param("complex", complexId)
                .param("analysis", ANALYSIS)
                .param("collection", COLLECTION)
                .update();
    }

    private String publicationStatus() {
        return jdbcClient
                .sql("""
                    SELECT status FROM building_register_profile_publication WHERE publication_id=:publication
                    """)
                .param("publication", PUBLICATION)
                .query(String.class)
                .single();
    }
}
