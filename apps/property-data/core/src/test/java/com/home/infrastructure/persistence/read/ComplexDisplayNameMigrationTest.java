package com.home.infrastructure.persistence.read;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.infrastructure.persistence.ingest.JdbcMigrationTestSupport;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplexDisplayNameMigrationTest extends JdbcMigrationTestSupport {

    @Test
    @DisplayName("V8은 source identity를 보존하면서 표시명 projection을 backfill하고 동기화한다")
    void v8BackfillsProjectionWithoutChangingSourceIdentityAndKeepsItSynchronized() {
        flyway(MigrationVersion.fromVersion("7")).clean();
        flyway(MigrationVersion.fromVersion("7")).migrate();

        Long regionId = jdbcClient
                .sql("SELECT id FROM region WHERE code = '11200108'")
                .query(Long.class)
                .single();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (990097, :regionId, '1120010800100970000', '서울 성동구 응봉동 97', 37.5500, 127.0300)
			""").param("regionId", regionId).update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, region_id, complex_pk, apt_seq, name, trade_name)
			VALUES (4677, 990097, :regionId, 'RTMS:11200-28', '11200-28', '대림(2차)', '대림(2차)')
			""").param("regionId", regionId).update();
        jdbcClient.sql("""
			INSERT INTO complex_name_alias (complex_id, alias_type, alias_name, normalized_name, source)
			VALUES (4677, 'RTMS_APT_NAME', '대림2차', '대림2차', 'RTMS')
			""").update();
        String identityBefore = identityChecksum();

        flyway(MigrationVersion.fromVersion("8")).migrate();

        assertThat(jdbcClient.sql("""
			SELECT display_name || '|' || search_name
			FROM complex
			WHERE id = 4677
			""").query(String.class).single()).isEqualTo("응봉동 대림(2차)|응봉동대림2차");
        assertThat(identityChecksum()).isEqualTo(identityBefore);

        jdbcClient.sql("UPDATE region SET name = '금호동' WHERE code = '11200108'").update();
        assertThat(jdbcClient
                        .sql("SELECT display_name FROM complex WHERE id = 4677")
                        .query(String.class)
                        .single())
                .isEqualTo("금호동 대림(2차)");

        jdbcClient
                .sql("UPDATE complex SET trade_name = '금호동대림' WHERE id = 4677")
                .update();
        assertThat(jdbcClient
                        .sql("SELECT display_name FROM complex WHERE id = 4677")
                        .query(String.class)
                        .single())
                .isEqualTo("금호동 대림");

        assertThat(indexDefinition("ix_complex_display_name_lower_trgm")).contains("gin_trgm_ops");
        assertThat(indexDefinition("ix_complex_search_name_trgm")).contains("gin_trgm_ops");
    }

    private String identityChecksum() {
        return jdbcClient.sql("""
			SELECT md5(string_agg(
				concat_ws('|', id, complex_pk, apt_seq, name, trade_name),
				E'\\n' ORDER BY id
			))
			FROM complex
			""").query(String.class).single();
    }

    private String indexDefinition(String indexName) {
        return jdbcClient
                .sql("""
			SELECT indexdef FROM pg_indexes
			WHERE schemaname = 'public' AND indexname = :indexName
			""")
                .param("indexName", indexName)
                .query(String.class)
                .optional()
                .orElse("");
    }
}
