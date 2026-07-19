package com.home.infrastructure.persistence.airead;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.infrastructure.persistence.ingest.JdbcMigrationTestSupport;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AiReadMigrationTest extends JdbcMigrationTestSupport {

    @Test
    @DisplayName("V10은 AI reader에 지역 계층 view만 SELECT로 공개한다")
    void v10PublishesRegionHierarchyToAiReader() throws SQLException {
        flyway(MigrationVersion.fromVersion("9")).clean();
        flyway(MigrationVersion.fromVersion("9")).migrate();

        flyway(MigrationVersion.fromVersion("10")).migrate();

        JdbcClient reader = JdbcClient.create(readerDataSource());
        assertThat(reader.sql("""
            SELECT child.region_code || '|' || parent.region_code || '|' || child.region_type
            FROM ai_read.region_fact child
            JOIN ai_read.region_fact parent ON parent.region_id = child.parent_region_id
            WHERE child.region_code = '11200108'
            """).query(String.class).single()).isEqualTo("11200108|11200|eup-myeon-dong");
        assertThat(jdbcClient
                        .sql("SELECT has_table_privilege(:role, 'ai_read.region_fact', 'SELECT')")
                        .param("role", AI_READER_ROLE)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThatThrownBy(() -> reader.sql("UPDATE ai_read.region_fact SET region_name = '변경'")
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied for view region_fact");
    }

    @Test
    @DisplayName("V9은 AI reader에 검증된 단지·정상 거래 view만 SELECT로 공개한다")
    void v9PublishesOnlyVerifiedPropertyFactsToAiReader() throws SQLException {
        flyway(MigrationVersion.fromVersion("8")).clean();
        flyway(MigrationVersion.fromVersion("8")).migrate();
        seedPropertyFacts();

        flyway(MigrationVersion.fromVersion("9")).migrate();

        JdbcClient reader = JdbcClient.create(readerDataSource());
        assertThat(reader.sql("""
			SELECT display_name || '|' || region_code || '|' || marker_safe
			FROM ai_read.complex_fact
			WHERE complex_id = 990001
			""").query(String.class).single()).isEqualTo("응봉동 AI 검증단지|11200108|true");
        assertThat(reader.sql("""
			SELECT latitude || '|' || longitude || '|' || coordinate_source
			FROM ai_read.complex_fact
			WHERE complex_id = 990001
			""").query(String.class).single()).isEqualTo("37.5510000|127.0310000|BUILDING_FOOTPRINT");

        assertThat(reader.sql("""
			SELECT count(*)
			FROM ai_read.trade_fact
			WHERE complex_id = 990001
			""").query(Long.class).single()).isEqualTo(1L);
        assertThat(reader.sql("""
			SELECT deal_amount_ten_thousand_krw || '|' || exclusive_area_square_meters
			FROM ai_read.trade_fact
			WHERE complex_id = 990001
			""").query(String.class).single()).isEqualTo("123456|84.91");

        assertThat(jdbcClient
                        .sql("SELECT has_table_privilege(:role, 'public.complex', 'SELECT')")
                        .param("role", AI_READER_ROLE)
                        .query(Boolean.class)
                        .single())
                .isFalse();
        assertThat(jdbcClient
                        .sql("SELECT has_table_privilege(:role, 'ai_read.complex_fact', 'SELECT')")
                        .param("role", AI_READER_ROLE)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThatThrownBy(() -> reader.sql("SELECT count(*) FROM public.complex")
                        .query(Long.class)
                        .single())
                .rootCause()
                .hasMessageContaining("permission denied for table complex");
        assertThatThrownBy(() -> reader.sql("""
			UPDATE ai_read.trade_fact
			SET deal_amount_ten_thousand_krw = 1
			WHERE trade_id = 990001
			""").update())
                .rootCause()
                .hasMessageContaining("permission denied for view trade_fact");
        assertThatThrownBy(() -> reader.sql("CREATE TABLE ai_read.forbidden_write (id bigint)")
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied for schema ai_read");
    }

    private void seedPropertyFacts() {
        Long regionId = jdbcClient
                .sql("SELECT id FROM region WHERE code = '11200108'")
                .query(Long.class)
                .single();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (990001, :regionId, '1120010800100970000', '서울 성동구 응봉동 97', 37.5500, 127.0300)
			""").param("regionId", regionId).update();
        jdbcClient.sql("""
			INSERT INTO complex (
			    id, parcel_id, region_id, complex_pk, apt_seq, name, trade_name, dong_cnt, unit_cnt, use_date
			) VALUES (
			    990001, 990001, :regionId, 'RTMS:AI-TEST', 'AI-TEST', 'AI 검증단지', 'AI 검증단지', 10, 500, DATE '2020-01-01'
			)
			""").param("regionId", regionId).update();
        jdbcClient.sql("""
			INSERT INTO complex_display_coordinate (
			    complex_id, latitude, longitude, coordinate_source, confidence, reason
			) VALUES (
			    990001, 37.5510, 127.0310, 'BUILDING_FOOTPRINT', 90, 'AI_READ_TEST'
			)
			""").update();
        jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    id, source, source_key, lawd_cd, deal_ymd, status
			) VALUES
			    (990001, 'RTMS', 'AI-TRADE-ACTIVE', '11200', '20260101', 'NORMALIZED'),
			    (990002, 'RTMS', 'AI-TRADE-DELETED', '11200', '20260102', 'CANCELED')
			""").update();
        jdbcClient.sql("""
			INSERT INTO trade (
			    id, complex_id, deal_date, deal_amount, floor, excl_area, apt_dong,
			    source, source_key, complex_pk, apt_seq, raw_ingest_id, deleted_at
			) VALUES
			    (990001, 990001, DATE '2026-01-01', 123456, 12, :area, '101동',
			     'RTMS', 'AI-TRADE-ACTIVE', 'RTMS:AI-TEST', 'AI-TEST', 990001, NULL),
			    (990002, 990001, DATE '2026-01-02', 999999, 13, :area, '101동',
			     'RTMS', 'AI-TRADE-DELETED', 'RTMS:AI-TEST', 'AI-TEST', 990002, now())
			""").param("area", new BigDecimal("84.91")).update();
    }

    private DataSource readerDataSource() throws SQLException {
        DriverManagerDataSource reader = new DriverManagerDataSource();
        reader.setDriverClassName("org.postgresql.Driver");
        try (Connection connection = dataSource.getConnection()) {
            reader.setUrl(connection.getMetaData().getURL());
        }
        reader.setUsername(AI_READER_ROLE);
        reader.setPassword(AI_READER_PASSWORD);
        return reader;
    }
}
