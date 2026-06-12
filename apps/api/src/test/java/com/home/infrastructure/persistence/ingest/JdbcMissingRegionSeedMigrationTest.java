package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcMissingRegionSeedMigrationTest extends JdbcMigrationTestSupport {

	@Test
	@DisplayName("V31 migration은 legacy seed에 없는 신설 읍면동 region을 보강하고 관계와 세대수 합계를 복구한다")
	void migrationSeedsMissingRegionsAndRepairsRelationsAndUnitCntSums() {
		migrateToVersion30();
		seedParcelsAndComplexesOnMissingRegions();

		migrateToLatest();

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

		assertThat(parcelRegionCode(3001L)).isEqualTo("43770256");
		assertThat(parcelRegionCode(3002L)).isEqualTo("41461262");
		assertThat(parcelRegionCode(3003L)).isEqualTo("11305108");
		assertThat(count("""
			SELECT count(*) FROM complex c
			JOIN parcel p ON p.id = c.parcel_id
			WHERE c.region_id IS DISTINCT FROM p.region_id
			""")).isZero();

		assertThat(regionUnitCntSum("43770256")).isEqualTo(620L);
		assertThat(regionUnitCntSum("43770")).isEqualTo(620L);
		assertThat(regionUnitCntSum("43")).isEqualTo(620L);
		assertThat(regionUnitCntSum("11305108")).isEqualTo(95L);
		assertThat(regionUnitCntSum("11305")).isEqualTo(95L);
		assertThat(regionUnitCntSum("11")).isEqualTo(95L);

		assertThat(regionCenter("43770256"))
			.containsEntry("center_lat", "36.9400000")
			.containsEntry("center_lng", "127.4800000");
	}

	private void migrateToVersion30() {
		var flyway = flyway(MigrationVersion.fromVersion("30"));
		flyway.clean();
		flyway.migrate();
		jdbcClient = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
	}

	private void migrateToLatest() {
		flyway(null).migrate();
		jdbcClient = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
	}

	private void seedParcelsAndComplexesOnMissingRegions() {
		jdbcClient.sql("""
			INSERT INTO parcel (id, pnu, latitude, longitude)
			VALUES
			    (3001, '4377025600100010001', 36.9400000, 127.4800000),
			    (3002, '4146126200100020002', 37.2300000, 127.2800000),
			    (3003, '1130510800100030003', 37.6400000, 127.0200000)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, name, unit_cnt)
			VALUES
			    (4001, 3001, 'RTMS:43770-9001', '대소 신축 단지', 620),
			    (4002, 3002, 'RTMS:41461-9002', '양지 신축 단지', 310),
			    (4003, 3003, 'RTMS:11305-9003', '도봉 신축 단지', 95)
			""").update();
		jdbcClient.sql("UPDATE complex SET unit_cnt = NULL WHERE id = 4002").update();
	}

	private long count(String sql) {
		return jdbcClient.sql(sql)
			.query(Long.class)
			.single();
	}

	private Map<String, Object> regionRow(String code) {
		return jdbcClient.sql("""
			SELECT r.name, parent.code AS parent_code, r.region_type
			FROM region r
			JOIN region parent ON parent.id = r.parent_id
			WHERE r.code = :code
			""")
			.param("code", code)
			.query((resultSet, rowNumber) -> Map.<String, Object>of(
				"name", resultSet.getString("name"),
				"parent_code", resultSet.getString("parent_code"),
				"region_type", resultSet.getString("region_type")
			))
			.single();
	}

	private String parcelRegionCode(Long parcelId) {
		return jdbcClient.sql("""
			SELECT r.code
			FROM parcel p
			JOIN region r ON r.id = p.region_id
			WHERE p.id = :parcelId
			""")
			.param("parcelId", parcelId)
			.query(String.class)
			.single();
	}

	private Long regionUnitCntSum(String code) {
		return jdbcClient.sql("SELECT unit_cnt_sum FROM region WHERE code = :code")
			.param("code", code)
			.query(Long.class)
			.single();
	}

	private Map<String, Object> regionCenter(String code) {
		return jdbcClient.sql("SELECT center_lat::text, center_lng::text FROM region WHERE code = :code")
			.param("code", code)
			.query((resultSet, rowNumber) -> Map.<String, Object>of(
				"center_lat", resultSet.getString("center_lat"),
				"center_lng", resultSet.getString("center_lng")
			))
			.single();
	}
}
