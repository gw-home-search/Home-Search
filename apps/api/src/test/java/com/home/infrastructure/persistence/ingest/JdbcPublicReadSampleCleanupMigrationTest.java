package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcPublicReadSampleCleanupMigrationTest extends JdbcMigrationTestSupport {

	@Test
	@DisplayName("V28 migration은 public read 경로에서 legacy local sample seed를 제거하고 개포동 주소를 복구한다")
	void migrationRemovesLegacyLocalSampleSeedFromPublicReadData() {
		migrateToVersion27();
		seedLegacySampleRows();
		seedGaepoReadRowsWithLegacySampleRegionName();

		migrateToLatest();

		assertThat(count("SELECT count(*) FROM complex WHERE id = 501 OR name ILIKE '%Sample%'")).isZero();
		assertThat(count("SELECT count(*) FROM raw_trade_ingest WHERE source_key LIKE 'sample-rtms-%'")).isZero();
		assertThat(count("SELECT count(*) FROM parcel WHERE address ILIKE 'Sample%'")).isZero();
		assertThat(jdbcClient.sql("SELECT name FROM region WHERE code = '11680103'")
			.query(String.class)
			.single()).isEqualTo("개포동");
		assertThat(jdbcClient.sql("SELECT address FROM parcel WHERE id = 2001")
			.query(String.class)
			.single()).isEqualTo("개포동 189");
		assertThat(count("SELECT count(*) FROM complex WHERE id = 1501 AND name = '개포주공4단지'")).isOne();
	}

	private void migrateToVersion27() {
		var flyway = flyway(MigrationVersion.fromVersion("27"));
		flyway.clean();
		flyway.migrate();
		jdbcClient = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
	}

	private void migrateToLatest() {
		flyway(null).migrate();
		jdbcClient = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
	}

	private void seedLegacySampleRows() {
		jdbcClient.sql("""
			INSERT INTO region (id, parent_id, code, name, region_type, center_lat, center_lng)
			VALUES
			    (1, NULL, '11', 'Seoul', 'si-do', 37.5663000, 126.9780000),
			    (11, 1, '11680', 'Gangnam-gu', 'si-gun-gu', 37.5172000, 127.0473000),
			    (111, 11, '11680103', 'Sample-dong', 'eup-myeon-dong', 37.5123000, 127.0456000)
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1001, 111, '1168010300101400001', 'Sample address', 37.5123000, 127.0456000)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (
			    id,
			    parcel_id,
			    complex_pk,
			    apt_seq,
			    name,
			    trade_name,
			    dong_cnt,
			    unit_cnt,
			    metadata_status,
			    metadata_source,
			    metadata_checked_at
			)
			VALUES (
			    501,
			    1001,
			    'COMPLEX-PK-501',
			    'APT-501',
			    'Sample Apartment',
			    'Sample trade name',
			    8,
			    740,
			    'RESOLVED',
			    'SEED',
			    now()
			)
			""").update();
		jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    id,
			    source,
			    source_key,
			    lawd_cd,
			    deal_ymd,
			    page_no,
			    payload,
			    payload_hash,
			    status,
			    failure_reason,
			    processed_at
			)
			VALUES
			    (90001, 'RTMS', 'sample-rtms-20251201', '11680', '202512', 1, '{}', 'hash-1', 'NORMALIZED', NULL, now()),
			    (90002, 'RTMS', 'sample-rtms-20251215', '11680', '202512', 1, '{}', 'hash-2', 'NORMALIZED', NULL, now()),
			    (90003, 'RTMS', 'sample-rtms-match-failed', '11680', '202512', 1, '{}', 'hash-3', 'MATCH_FAILED', 'sample failed match', now()),
			    (90004, 'RTMS', 'sample-rtms-20251201', '11680', '202512', 1, '{}', 'hash-4', 'DUPLICATE', 'duplicate sample source key', now())
			""").update();
		jdbcClient.sql("""
			INSERT INTO trade (
			    id,
			    complex_id,
			    deal_date,
			    deal_amount,
			    floor,
			    excl_area,
			    apt_dong,
			    source,
			    source_key,
			    complex_pk,
			    apt_seq,
			    raw_ingest_id
			)
			VALUES
			    (9001, 501, DATE '2025-12-01', 125000, 12, 84.93, '101', 'RTMS', 'sample-rtms-20251201', 'COMPLEX-PK-501', 'APT-501', 90001),
			    (9002, 501, DATE '2025-12-15', 130000, 15, 84.93, '101', 'RTMS', 'sample-rtms-20251215', 'COMPLEX-PK-501', 'APT-501', 90002)
			""").update();
		jdbcClient.sql("""
			INSERT INTO trade_source_key_registry (source, source_key, raw_ingest_id, trade_id)
			VALUES
			    ('RTMS', 'sample-rtms-20251201', 90001, 9001),
			    ('RTMS', 'sample-rtms-20251215', 90002, 9002)
			""").update();
	}

	private void seedGaepoReadRowsWithLegacySampleRegionName() {
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (2001, 111, '1168010300101890000', 'Sample-dong 189', 37.4890000, 127.0710000)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name)
			VALUES (1501, 2001, 'RTMS:11680-289', '11680-289', '개포주공4단지', '개포주공4단지')
			""").update();
	}

	private long count(String sql) {
		return jdbcClient.sql(sql)
			.query(Long.class)
			.single();
	}
}
