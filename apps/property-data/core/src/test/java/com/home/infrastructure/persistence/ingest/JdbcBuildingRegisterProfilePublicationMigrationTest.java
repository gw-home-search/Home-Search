package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.domain.complex.buildingprofile.BuildingProfileField;
import com.home.domain.complex.buildingprofile.BuildingProfileScope;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcBuildingRegisterProfilePublicationMigrationTest extends JdbcMigrationTestSupport {

    @Test
    @DisplayName("83개 건축물대장 필드와 publication lineage를 append-only schema로 생성한다")
    void createsCompleteTypedPublicationSchema() {
        flyway(null).clean();
        flyway(null).migrate();

        assertThat(BuildingProfileField.values()).hasSize(83);
        assertThat(countFields(BuildingProfileScope.SITE)).isEqualTo(35);
        assertThat(countFields(BuildingProfileScope.BUILDING)).isEqualTo(39);
        assertThat(countFields(BuildingProfileScope.HIERARCHY)).isEqualTo(9);

        assertThat(List.of(
                        "building_register_profile_publication",
                        "building_register_profile_site",
                        "building_register_profile_building",
                        "building_register_profile_hierarchy",
                        "complex_building_register_profile_summary",
                        "building_register_profile_field_evidence"))
                .allMatch(table -> regclass(table) != null);

        assertThat(columnCount("building_register_profile_site", BuildingProfileScope.SITE)).isEqualTo(35);
        assertThat(columnCount("building_register_profile_building", BuildingProfileScope.BUILDING)).isEqualTo(39);
        assertThat(columnCount("building_register_profile_hierarchy", BuildingProfileScope.HIERARCHY)).isEqualTo(9);
        assertThat(jdbcClient.sql("""
                    SELECT indexdef
                    FROM pg_indexes
                    WHERE schemaname='public'
                      AND tablename='building_register_profile_publication'
                      AND indexname='uq_brpp_one_published'
                    """).query(String.class).optional())
                .hasValueSatisfying(index -> assertThat(index).contains("WHERE", "status", "PUBLISHED"));
    }

    @Test
    @DisplayName("publication storage는 runtime role에 삭제 없는 최소 권한만 부여한다")
    void grantsAppendOnlyPublicationPrivileges() {
        flyway(null).clean();
        flyway(null).migrate();

        List<String> writableTables = List.of(
                "building_register_profile_publication",
                "building_register_profile_site",
                "building_register_profile_building",
                "building_register_profile_hierarchy",
                "complex_building_register_profile_summary",
                "building_register_profile_field_evidence");
        assertThat(writableTables).allSatisfy(table -> {
            assertThat(hasTablePrivilege(table, "SELECT,INSERT,UPDATE")).isTrue();
            assertThat(hasTablePrivilege(table, "DELETE")).isFalse();
        });
    }

    @Test
    @DisplayName("불완전 publication은 거부하고 검증 완료 publication만 원자적으로 전환한다")
    void atomicallyPublishesOnlyCompleteValidatedPublication() {
        flyway(null).clean();
        flyway(null).migrate();
        seedPublicationLineage();
        insertPublication("00000000-0000-0000-0000-000000000101", "PUBLISHED", 0, 0, "RULES_V1");
        insertPublication("00000000-0000-0000-0000-000000000102", "VALIDATED", 1, 0, "RULES_V2");

        assertThatThrownBy(() -> jdbcClient.sql(
                                "SELECT publish_building_register_profile(CAST(:id AS uuid))")
                        .param("id", "00000000-0000-0000-0000-000000000102")
                        .query(Object.class)
                        .single())
                .hasMessageContaining("publication row counts are incomplete");
        assertThat(publicationStatus("00000000-0000-0000-0000-000000000101"))
                .isEqualTo("PUBLISHED");

        insertPublication("00000000-0000-0000-0000-000000000103", "VALIDATED", 0, 0, "RULES_V3");
        jdbcClient.sql("SELECT publish_building_register_profile(CAST(:id AS uuid))")
                .param("id", "00000000-0000-0000-0000-000000000103")
                .query(Object.class)
                .single();

        assertThat(publicationStatus("00000000-0000-0000-0000-000000000101"))
                .isEqualTo("SUPERSEDED");
        assertThat(publicationStatus("00000000-0000-0000-0000-000000000103"))
                .isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("운영 backfill은 검증된 complex 값으로 null만 채우고 partial MAX와 기존 값을 보존한다")
    void backfillsOnlyNullOperationalColumnsFromVerifiedComplexValues() {
        flyway(null).clean();
        flyway(null).migrate();
        seedPublicationLineage();
        insertPublication("00000000-0000-0000-0000-000000000104", "PUBLISHED", 0, 0, "RULES_V4");
        jdbcClient.sql("""
                    INSERT INTO region(id,code,name,region_type)
                    VALUES (99001,'1168010399','sample','eup-myeon-dong')
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO parcel(id,region_id,pnu,address,latitude,longitude)
                    VALUES (99001,99001,'1168010300101400009','parcel',37.5,127.0)
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO complex(id,parcel_id,complex_pk,name,unit_cnt,bc_rat)
                    VALUES (99001,99001,'COMPLEX-99001','complex',999,20.00)
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO complex_building_register_profile_summary(
                      publication_id,complex_id,
                      household_scope,household_quality,household_count,family_count,unit_count,
                      ratio_scope,ratio_quality,building_coverage_rate,
                      building_scope,building_quality,max_ground_floor_count,
                      address_scope,address_quality,road_address)
                    VALUES (CAST(:id AS uuid),99001,
                      'COMPLEX','VERIFIED',100,80,110,
                      'COMPLEX','VERIFIED',30.00,
                      'COMPLEX','PARTIAL',99,
                      'PARCEL','PNU_FALLBACK','sample road')
                    """).param("id", "00000000-0000-0000-0000-000000000104").update();

        jdbcClient.sql("SELECT backfill_building_register_profile_operational_columns(CAST(:id AS uuid))")
                .param("id", "00000000-0000-0000-0000-000000000104")
                .query(Object.class)
                .single();

        assertThat(jdbcClient.sql("SELECT unit_cnt FROM complex WHERE id=99001").query(Integer.class).single())
                .isEqualTo(999);
        assertThat(jdbcClient.sql("SELECT bc_rat FROM complex WHERE id=99001")
                        .query(java.math.BigDecimal.class).single())
                .isEqualByComparingTo("20.00");
        assertThat(jdbcClient.sql("SELECT family_cnt FROM complex WHERE id=99001").query(Long.class).single())
                .isEqualTo(80L);
        assertThat(jdbcClient.sql("SELECT ho_cnt FROM complex WHERE id=99001").query(Long.class).single())
                .isEqualTo(110L);
        assertThat(jdbcClient.sql("SELECT max_grnd_flr_cnt FROM complex WHERE id=99001")
                        .query(Long.class).optional())
                .isEmpty();
        assertThat(jdbcClient.sql("SELECT road_address FROM parcel WHERE id=99001")
                        .query(String.class).single())
                .isEqualTo("sample road");
    }

    private void seedPublicationLineage() {
        jdbcClient.sql("""
                    INSERT INTO building_register_collection_campaign(
                      collection_id,mode,strategy,to_complex_id,status,purpose,target_scope,selection_seed,sample_size)
                    VALUES ('00000000-0000-0000-0000-000000000001','profile','COMPARE_RECAP_TITLE',1,
                      'CREATED','PROFILE_DISCOVERY','VALIDATION_SAMPLE','publication-test',1)
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO building_register_profile_parse_run(
                      parse_run_id,source_collection_id,parser_version,status)
                    VALUES ('00000000-0000-0000-0000-000000000002',
                      '00000000-0000-0000-0000-000000000001','PROFILE_V1','RUNNING')
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO building_register_profile_analysis_run(
                      analysis_run_id,collection_id,parse_run_id,rules_version,status)
                    VALUES ('00000000-0000-0000-0000-000000000003',
                      '00000000-0000-0000-0000-000000000001',
                      '00000000-0000-0000-0000-000000000002','RULES_SOURCE','RUNNING')
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO building_register_profile_projection_run(
                      projection_run_id,analysis_run_id,collection_id,parse_run_id,
                      projection_version,minimum_readiness,status)
                    VALUES ('00000000-0000-0000-0000-000000000004',
                      '00000000-0000-0000-0000-000000000003',
                      '00000000-0000-0000-0000-000000000001',
                      '00000000-0000-0000-0000-000000000002','PROJECTION_V1',0.5,'RUNNING')
                    """).update();
    }

    private void insertPublication(String id, String status, int expectedSummaryCount, int summaryCount, String rules) {
        String lifecycleColumns = "PUBLISHED".equals(status)
                ? "validated_at,published_at"
                : "validated_at";
        String lifecycleValues = "PUBLISHED".equals(status) ? "now(),now()" : "now()";
        jdbcClient.sql("""
                    INSERT INTO building_register_profile_publication(
                      publication_id,source_collection_id,source_parse_run_id,source_analysis_run_id,
                      source_projection_run_id,rules_version,parser_version,status,
                      expected_site_count,expected_building_count,expected_hierarchy_count,
                      expected_evidence_count,expected_summary_count,site_count,building_count,
                      hierarchy_count,evidence_count,summary_count,content_sha256,%s)
                    VALUES (CAST(:id AS uuid),'00000000-0000-0000-0000-000000000001',
                      '00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000003',
                      '00000000-0000-0000-0000-000000000004',:rules,'PROFILE_V1',:status,
                      0,0,0,0,:expected,0,0,0,0,:actual,repeat('a',64),%s)
                    """.formatted(lifecycleColumns, lifecycleValues))
                .param("id", id)
                .param("rules", rules)
                .param("status", status)
                .param("expected", expectedSummaryCount)
                .param("actual", summaryCount)
                .update();
    }

    private String publicationStatus(String id) {
        return jdbcClient.sql("""
                    SELECT status FROM building_register_profile_publication
                    WHERE publication_id=CAST(:id AS uuid)
                    """).param("id", id).query(String.class).single();
    }

    private long countFields(BuildingProfileScope scope) {
        return java.util.Arrays.stream(BuildingProfileField.values())
                .filter(field -> field.scope() == scope)
                .count();
    }

    private int columnCount(String table, BuildingProfileScope scope) {
        List<String> expected = java.util.Arrays.stream(BuildingProfileField.values())
                .filter(field -> field.scope() == scope)
                .map(field -> field.name().toLowerCase(java.util.Locale.ROOT))
                .toList();
        return jdbcClient.sql("""
                    SELECT count(*)
                    FROM information_schema.columns
                    WHERE table_schema='public'
                      AND table_name=:table
                      AND column_name = ANY(CAST(:columns AS text[]))
                    """)
                .param("table", table)
                .param("columns", expected.toArray(String[]::new))
                .query(Integer.class)
                .single();
    }

    private String regclass(String name) {
        return jdbcClient.sql("SELECT to_regclass(:name)::text")
                .param("name", name)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private boolean hasTablePrivilege(String table, String privileges) {
        return jdbcClient.sql("SELECT has_table_privilege(:role,:table,:privileges)")
                .param("role", PROPERTY_RUNTIME_ROLE)
                .param("table", table)
                .param("privileges", privileges)
                .query(Boolean.class)
                .single();
    }
}
