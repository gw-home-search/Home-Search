package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcCleanCoreReferenceDataMigrationTest extends JdbcMigrationTestSupport {

    @Test
    @DisplayName("clean reference data migration은 전국 region과 기존 parcel/complex reference data를 보강한다")
    void cleanReferenceDataMigrationSeedsRegionsAndBackfillsExistingRows() {
        migrateToVersion1();
        seedRuntimeRowsWithoutReferenceData();

        migrateToLatest();

        assertThat(appliedMigrationVersions())
                .containsExactly(
                        "1", "2", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18",
                        "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "32", "33");
        assertThat(regclass("batch.BATCH_JOB_INSTANCE")).isEqualTo("batch.batch_job_instance");
        assertThat(count("SELECT count(*) FROM region WHERE region_type = 'si-do'"))
                .isGreaterThanOrEqualTo(17);
        assertThat(count("SELECT count(*) FROM region WHERE region_type = 'si-gun-gu'"))
                .isGreaterThan(200);
        assertThat(count("SELECT count(*) FROM region WHERE region_type = 'eup-myeon-dong'"))
                .isGreaterThan(4_000);
        assertThat(regionRow("43770256"))
                .containsEntry("name", "대소읍")
                .containsEntry("parent_code", "43770")
                .containsEntry("region_type", "eup-myeon-dong");
        assertThat(regionRow("41461262"))
                .containsEntry("name", "양지읍")
                .containsEntry("parent_code", "41461")
                .containsEntry("region_type", "eup-myeon-dong");
        assertThat(regionRow("11305108"))
                .containsEntry("name", "도봉동")
                .containsEntry("parent_code", "11305")
                .containsEntry("region_type", "eup-myeon-dong");
        assertThat(regionCenter("28")).containsExactly(new BigDecimal("37.4562259"), new BigDecimal("126.7059693"));
        assertThat(regionCenter("41")).containsExactly(new BigDecimal("37.2117556"), new BigDecimal("127.3760723"));
        assertThat(regionCenter("47")).containsExactly(new BigDecimal("36.5250729"), new BigDecimal("128.8543550"));
        assertThat(regionCenter("50110")).containsExactly(new BigDecimal("33.5000829"), new BigDecimal("126.5314575"));

        assertThat(parcelRegionAndAddress(1001L))
                .containsEntry("region_code", "11680104")
                .containsEntry("address", "청담동 134-38");
        assertThat(parcelRegionAndAddress(1002L))
                .containsEntry("region_code", "11215105")
                .containsEntry("address", "자양동 624");
        assertThat(parcelRegionAndAddress(1003L))
                .containsEntry("region_code", "43770256")
                .containsEntry("address", "대소읍 1-1");

        assertThat(complexMetadata(2001L))
                .containsEntry("dong_cnt", 5)
                .containsEntry("unit_cnt", 708)
                .containsEntry("use_date", LocalDate.of(2011, 10, 21))
                .containsEntry("metadata_status", "RESOLVED")
                .containsEntry("metadata_source", "LEGACY_CSV");
        assertThat(complexMetadata(2002L))
                .containsEntry("dong_cnt", 1)
                .containsEntry("unit_cnt", 18)
                .containsEntry("use_date", LocalDate.of(2012, 6, 5))
                .containsEntry("metadata_status", "RESOLVED")
                .containsEntry("metadata_source", "LEGACY_CSV");
    }

    @Test
    @DisplayName("latest fresh baseline은 Java parity로 확정한 schema와 seed fingerprint를 유지한다")
    void sqlV2FreshBaselineMatchesGoldenFingerprint() {
        flyway(null).clean();
        flyway(null).migrate();
        jdbcClient = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);

        assertThat(schemaFingerprint()).isEqualTo("2c5f394bcd1e8471157980c8f11b8621");
        assertThat(seedFingerprint()).isEqualTo("4cc3b683cff740f5eb20dc09271f117d");
        assertThat(sequenceFingerprint()).isEqualTo("588869ab552608ed168a0b29266f5e91");
    }

    private void migrateToVersion1() {
        var flyway = flyway(MigrationVersion.fromVersion("1"));
        flyway.clean();
        flyway.migrate();
        jdbcClient = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
    }

    private void migrateToLatest() {
        flyway(null).migrate();
        jdbcClient = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
    }

    private void seedRuntimeRowsWithoutReferenceData() {
        jdbcClient.sql("""
			INSERT INTO parcel (id, pnu, latitude, longitude)
			VALUES
			    (1001, '1168010400101340038', 37.5190000, 127.0490000),
			    (1002, '1121510500106240000', 37.5320000, 127.0840000),
			    (1003, '4377025600100010001', 36.9400000, 127.4800000)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name)
			VALUES
			    (2001, 1001, 'RTMS:11680-4004', '11680-4004', '청담자이', '청담자이'),
			    (2002, 1002, 'RTMS:11215-1', '11215-1', 'legacy exact pnu target', 'legacy exact pnu target'),
			    (2003, 1003, 'RTMS:43770-9001', '43770-9001', '대소 신축 단지', '대소 신축 단지')
			""").update();
    }

    private java.util.List<String> appliedMigrationVersions() {
        return jdbcClient.sql("""
			SELECT version
			FROM flyway_schema_history
			WHERE success
			  AND version IS NOT NULL
			ORDER BY installed_rank
			""").query(String.class).list();
    }

    private long count(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }

    private String schemaFingerprint() {
        return jdbcClient.sql("""
			WITH objects AS (
			    SELECT 'column|' || table_schema || '|' || table_name || '|' || ordinal_position || '|'
			        || column_name || '|' || data_type || '|' || is_nullable || '|' || coalesce(column_default, '') AS value
			    FROM information_schema.columns
			    WHERE table_schema IN ('public', 'reference', 'batch')
			    UNION ALL
			    SELECT 'constraint|' || n.nspname || '|' || c.relname || '|' || con.conname || '|'
			        || pg_get_constraintdef(con.oid, true)
			    FROM pg_constraint con
			    JOIN pg_class c ON c.oid = con.conrelid
			    JOIN pg_namespace n ON n.oid = c.relnamespace
			    WHERE n.nspname IN ('public', 'reference', 'batch')
			    UNION ALL
			    SELECT 'index|' || schemaname || '|' || tablename || '|' || indexname || '|' || indexdef
			    FROM pg_indexes
			    WHERE schemaname IN ('public', 'reference', 'batch')
			    UNION ALL
			    SELECT 'trigger|' || event_object_schema || '|' || event_object_table || '|' || trigger_name || '|'
			        || action_timing || '|' || event_manipulation || '|' || action_statement
			    FROM information_schema.triggers
			    WHERE event_object_schema IN ('public', 'reference', 'batch')
			    UNION ALL
			    SELECT 'grant|' || table_schema || '|' || table_name || '|' || grantee || '|' || privilege_type
			    FROM information_schema.role_table_grants
			    WHERE table_schema IN ('public', 'reference', 'batch')
			)
			SELECT md5(string_agg(value, E'\\n' ORDER BY value)) FROM objects
			""").query(String.class).single();
    }

    private String seedFingerprint() {
        return jdbcClient.sql("""
			SELECT md5(concat_ws('|',
			    (SELECT count(*)::text FROM region),
			    (SELECT md5(string_agg(concat_ws('|', id, code, name, parent_id), E'\\n' ORDER BY id)) FROM region),
			    (SELECT count(*)::text FROM parcel),
			    (SELECT md5(string_agg(concat_ws('|', id, pnu, address), E'\\n' ORDER BY id)) FROM parcel),
			    (SELECT count(*)::text FROM complex),
			    (SELECT md5(string_agg(concat_ws('|', id, complex_pk, apt_seq, name, trade_name, display_name), E'\\n' ORDER BY id)) FROM complex)
			))
			""").query(String.class).single();
    }

    private String sequenceFingerprint() {
        return jdbcClient.sql("""
			SELECT md5(string_agg(concat_ws('|', sequence_schema, sequence_name, start_value, minimum_value,
			    maximum_value, increment), E'\\n' ORDER BY sequence_schema, sequence_name))
			FROM information_schema.sequences
			WHERE sequence_schema IN ('public', 'reference', 'batch')
			""").query(String.class).single();
    }

    private String regclass(String relationName) {
        return jdbcClient
                .sql("SELECT to_regclass(:relationName)::text")
                .param("relationName", relationName)
                .query(String.class)
                .single();
    }

    private Map<String, Object> regionRow(String code) {
        return jdbcClient
                .sql("""
			SELECT r.name, parent.code AS parent_code, r.region_type
			FROM region r
			JOIN region parent ON parent.id = r.parent_id
			WHERE r.code = :code
			""")
                .param("code", code)
                .query((resultSet, rowNumber) -> Map.<String, Object>of(
                        "name", resultSet.getString("name"),
                        "parent_code", resultSet.getString("parent_code"),
                        "region_type", resultSet.getString("region_type")))
                .single();
    }

    private java.util.List<BigDecimal> regionCenter(String code) {
        return jdbcClient
                .sql("SELECT center_lat, center_lng FROM region WHERE code = :code")
                .param("code", code)
                .query((resultSet, rowNumber) ->
                        java.util.List.of(resultSet.getBigDecimal("center_lat"), resultSet.getBigDecimal("center_lng")))
                .single();
    }

    private Map<String, Object> parcelRegionAndAddress(Long parcelId) {
        return jdbcClient
                .sql("""
			SELECT r.code AS region_code, p.address
			FROM parcel p
			JOIN region r ON r.id = p.region_id
			WHERE p.id = :parcelId
			""")
                .param("parcelId", parcelId)
                .query((resultSet, rowNumber) -> Map.<String, Object>of(
                        "region_code", resultSet.getString("region_code"),
                        "address", resultSet.getString("address")))
                .single();
    }

    private Map<String, Object> complexMetadata(Long complexId) {
        return jdbcClient
                .sql("""
			SELECT dong_cnt, unit_cnt, use_date, metadata_status, metadata_source
			FROM complex
			WHERE id = :complexId
			""")
                .param("complexId", complexId)
                .query((resultSet, rowNumber) -> Map.<String, Object>of(
                        "dong_cnt", resultSet.getInt("dong_cnt"),
                        "unit_cnt", resultSet.getInt("unit_cnt"),
                        "use_date", resultSet.getObject("use_date", LocalDate.class),
                        "metadata_status", resultSet.getString("metadata_status"),
                        "metadata_source", resultSet.getString("metadata_source")))
                .single();
    }
}
