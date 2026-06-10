package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcNationwideRegionAndMetadataBackfillMigrationTest extends JdbcMigrationTestSupport {

	@Test
	@DisplayName("V29 migration은 legacy 전국 region과 단지 세대수 metadata를 백필한다")
	void migrationBackfillsNationwideRegionsParcelRegionIdsAndComplexUnitCounts() {
		migrateToVersion28();
		seedRuntimeRowsWithoutRegionAndMetadata();

		migrateToLatest();

		assertThat(count("SELECT count(*) FROM region WHERE region_type = 'si-do'")).isGreaterThanOrEqualTo(17);
		assertThat(count("SELECT count(*) FROM region WHERE region_type = 'si-gun-gu'")).isGreaterThan(200);
		assertThat(count("SELECT count(*) FROM region WHERE region_type = 'eup-myeon-dong'")).isGreaterThan(4_000);
		assertThat(regionName("11680104")).isEqualTo("청담동");

		assertThat(parcelRegionAndAddress(1001L))
			.containsEntry("region_code", "11680104")
			.containsEntry("address", "청담동 134-38");
		assertThat(parcelRegionAndAddress(1002L))
			.containsEntry("region_code", "11215105")
			.containsEntry("address", "자양동 624");

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

	private void migrateToVersion28() {
		var flyway = flyway(MigrationVersion.fromVersion("28"));
		flyway.clean();
		flyway.migrate();
		jdbcClient = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
	}

	private void migrateToLatest() {
		flyway(null).migrate();
		jdbcClient = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
	}

	private void seedRuntimeRowsWithoutRegionAndMetadata() {
		jdbcClient.sql("""
			INSERT INTO parcel (id, pnu, latitude, longitude)
			VALUES
			    (1001, '1168010400101340038', 37.5190000, 127.0490000),
			    (1002, '1121510500106240000', 37.5320000, 127.0840000)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name)
			VALUES
			    (2001, 1001, 'RTMS:11680-4004', '11680-4004', '청담자이', '청담자이'),
			    (2002, 1002, 'RTMS:11215-1', '11215-1', 'legacy exact pnu target', 'legacy exact pnu target')
			""").update();
	}

	private long count(String sql) {
		return jdbcClient.sql(sql)
			.query(Long.class)
			.single();
	}

	private String regionName(String code) {
		return jdbcClient.sql("SELECT name FROM region WHERE code = :code")
			.param("code", code)
			.query(String.class)
			.single();
	}

	private Map<String, Object> parcelRegionAndAddress(Long parcelId) {
		return jdbcClient.sql("""
			SELECT r.code AS region_code, p.address
			FROM parcel p
			JOIN region r ON r.id = p.region_id
			WHERE p.id = :parcelId
			""")
			.param("parcelId", parcelId)
			.query((resultSet, rowNumber) -> Map.<String, Object>of(
				"region_code", resultSet.getString("region_code"),
				"address", resultSet.getString("address")
			))
			.single();
	}

	private Map<String, Object> complexMetadata(Long complexId) {
		return jdbcClient.sql("""
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
				"metadata_source", resultSet.getString("metadata_source")
			))
			.single();
	}
}
