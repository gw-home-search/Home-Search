package com.home.infrastructure.persistence.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.infrastructure.persistence.ingest.JdbcMigrationTestSupport;
import java.util.List;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StagedComplexSearchMigrationTest extends JdbcMigrationTestSupport {

    @Test
    @DisplayName("V40은 search index만 추가하고 core row와 식별자를 변경하지 않는다")
    void v40AddsOnlySearchIndexesWithoutChangingCoreRowsOrIdentifiers() {
        flyway(MigrationVersion.fromVersion("39")).clean();
        flyway(MigrationVersion.fromVersion("39")).migrate();
        seedCoreRows();

        List<Long> countsBefore = coreCounts();
        String checksumBefore = identityChecksum();

        flyway(MigrationVersion.fromVersion("40")).migrate();

        assertThat(coreCounts()).isEqualTo(countsBefore).containsExactly(1L, 1L, 1L, 1L);
        assertThat(identityChecksum()).isEqualTo(checksumBefore);
        assertThat(latestSuccessfulMigration()).isEqualTo("40");
        assertThat(flyway(MigrationVersion.fromVersion("40")).validateWithResult().validationSuccessful)
                .isTrue();
        assertThat(indexDefinition("ix_complex_display_name_lower_prefix"))
                .contains("text_pattern_ops")
                .contains("lower");
        assertThat(indexDefinition("ix_complex_name_lower_prefix")).contains("text_pattern_ops");
        assertThat(indexDefinition("ix_complex_trade_name_lower_prefix")).contains("text_pattern_ops");
        assertThat(indexDefinition("ix_complex_search_name_prefix")).contains("text_pattern_ops");
        assertThat(indexDefinition("ix_complex_name_alias_alias_name_lower_prefix"))
                .contains("text_pattern_ops");
        assertThat(indexDefinition("ix_complex_name_alias_normalized_name_prefix"))
                .contains("text_pattern_ops");
        assertThat(indexDefinition("ix_parcel_address_simple_fts"))
                .contains("USING gin")
                .contains("to_tsvector('simple'");
    }

    private void seedCoreRows() {
        Long regionId = jdbcClient
                .sql("SELECT id FROM region WHERE code = '11440124'")
                .query(Long.class)
                .single();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1001, :regionId, '1144012400100010001', '서울 마포구 아현동 1', 37.5500, 126.9500)
			""").param("regionId", regionId).update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, region_id, complex_pk, apt_seq, name, trade_name)
			VALUES (501, 1001, :regionId, 'COMPLEX-PK-501', 'APT-501', '마포래미안푸르지오', '마포래미안푸르지오')
			""").param("regionId", regionId).update();
        jdbcClient.sql("""
			INSERT INTO complex_name_alias (complex_id, alias_type, alias_name, normalized_name, source)
			VALUES (501, 'RTMS_APT_NAME', '마래푸', '마래푸', 'RTMS')
			""").update();
        jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    id, source, source_key, lawd_cd, deal_ymd, page_no,
			    payload, payload_hash, status, processed_at
			)
			VALUES (90001, 'RTMS', 'migration-identity-1', '11440', '202607', 1,
			        '{}', 'migration-hash-1', 'NORMALIZED', now())
			""").update();
        jdbcClient.sql("""
			INSERT INTO trade (
			    id, complex_id, deal_date, deal_amount, floor, excl_area, apt_dong,
			    source, source_key, complex_pk, apt_seq, raw_ingest_id
			)
			VALUES (9001, 501, DATE '2026-07-01', 150000, 15, 84.95, '101',
			        'RTMS', 'migration-identity-1', 'COMPLEX-PK-501', 'APT-501', 90001)
			""").update();
    }

    private List<Long> coreCounts() {
        return jdbcClient.sql("""
			SELECT row_count
			FROM (
			    SELECT 1 AS ordinal, count(*) AS row_count FROM complex
			    UNION ALL SELECT 2, count(*) FROM complex_name_alias
			    UNION ALL SELECT 3, count(*) FROM parcel
			    UNION ALL SELECT 4, count(*) FROM trade
			) counts
			ORDER BY ordinal
			""").query(Long.class).list();
    }

    private String identityChecksum() {
        return jdbcClient.sql("""
			SELECT md5(concat_ws('|',
			    (SELECT string_agg(concat_ws(':', id, complex_pk, apt_seq), ',' ORDER BY id) FROM complex),
			    (SELECT string_agg(concat_ws(':', id, complex_id, source, source_key), ',' ORDER BY id) FROM complex_name_alias),
			    (SELECT string_agg(concat_ws(':', id, pnu), ',' ORDER BY id) FROM parcel),
			    (SELECT string_agg(concat_ws(':', id, complex_id, source, source_key, complex_pk, apt_seq), ',' ORDER BY id) FROM trade)
			))
			""").query(String.class).single();
    }

    private String latestSuccessfulMigration() {
        return jdbcClient.sql("""
			SELECT version
			FROM flyway_schema_history
			WHERE success AND version IS NOT NULL
			ORDER BY installed_rank DESC
			LIMIT 1
			""").query(String.class).single();
    }

    private String indexDefinition(String indexName) {
        return jdbcClient
                .sql("""
			SELECT indexdef
			FROM pg_indexes
			WHERE schemaname = 'public' AND indexname = :indexName
			""")
                .param("indexName", indexName)
                .query(String.class)
                .optional()
                .orElse("");
    }
}
