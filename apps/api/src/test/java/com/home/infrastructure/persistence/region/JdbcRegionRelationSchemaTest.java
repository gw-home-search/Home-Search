package com.home.infrastructure.persistence.region;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.home.infrastructure.persistence.ingest.JdbcMigrationTestSupport;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcRegionRelationSchemaTest extends JdbcMigrationTestSupport {

	@Test
	@DisplayName("V30 migration은 complex region 직접 관계와 region 세대수 합계를 초기화한다")
	void migrationAddsComplexRegionRelationAndRegionUnitCountSum() {
		migrateToVersion29();
		seedExistingRegionParcelAndComplexRows();

		migrateToLatest();

		assertThat(complexRegionCodes())
			.containsEntry("first", "11680104")
			.containsEntry("second", "11680104")
			.containsEntry("unmatched", null);
		assertThat(regionUnitCountSums())
			.containsEntry("sido", 1200L)
			.containsEntry("sigungu", 1200L)
			.containsEntry("emd", 1200L)
			.containsEntry("empty", null);

		jdbcClient.sql("""
			UPDATE parcel
			SET region_id = (SELECT id FROM region WHERE code = '11680105')
			WHERE id = 1001
			""").update();

		assertThat(complexRegionCodes())
			.containsEntry("first", "11680105")
			.containsEntry("second", "11680105");
	}

	private void migrateToVersion29() {
		var flyway = flyway(MigrationVersion.fromVersion("29"));
		flyway.clean();
		flyway.migrate();
		jdbcClient = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
	}

	private void migrateToLatest() {
		flyway(null).migrate();
		jdbcClient = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
	}

	private void seedExistingRegionParcelAndComplexRows() {
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES
			    (
			        1001,
			        (SELECT id FROM region WHERE code = '11680105'),
			        '1168010400100010001',
			        'Cheongdam 1',
			        37.5194,
			        127.0496
			    ),
			    (
			        1002,
			        (SELECT id FROM region WHERE code = '11680105'),
			        '9999999900100010001',
			        'Unknown 1',
			        37.5000,
			        127.0000
			    )
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt)
			VALUES
			    (2001, 1001, 'complex-2001', 'apt-2001', 'Cheongdam A', 740),
			    (2002, 1001, 'complex-2002', 'apt-2002', 'Cheongdam B', 460),
			    (2003, 1002, 'complex-2003', 'apt-2003', 'Unknown A', 500)
			""").update();
	}

	private Map<String, String> complexRegionCodes() {
		return jdbcClient.sql("""
			SELECT
			    max(r.code) FILTER (WHERE c.id = 2001) AS first,
			    max(r.code) FILTER (WHERE c.id = 2002) AS second,
			    max(r.code) FILTER (WHERE c.id = 2003) AS unmatched
			FROM complex c
			LEFT JOIN region r ON r.id = c.region_id
			""")
			.query((resultSet, rowNumber) -> {
				Map<String, String> values = new java.util.HashMap<>();
				values.put("first", resultSet.getString("first"));
				values.put("second", resultSet.getString("second"));
				values.put("unmatched", resultSet.getString("unmatched"));
				return values;
			})
			.single();
	}

	private Map<String, Long> regionUnitCountSums() {
		return jdbcClient.sql("""
			SELECT
			    max(unit_cnt_sum) FILTER (WHERE code = '11') AS sido,
			    max(unit_cnt_sum) FILTER (WHERE code = '11680') AS sigungu,
			    max(unit_cnt_sum) FILTER (WHERE code = '11680104') AS emd,
			    max(unit_cnt_sum) FILTER (WHERE code = '11680105') AS empty
			FROM region
			""")
			.query((resultSet, rowNumber) -> {
				Map<String, Long> values = new java.util.HashMap<>();
				values.put("sido", resultSet.getObject("sido", Long.class));
				values.put("sigungu", resultSet.getObject("sigungu", Long.class));
				values.put("emd", resultSet.getObject("emd", Long.class));
				values.put("empty", resultSet.getObject("empty", Long.class));
				return values;
			})
			.single();
	}
}
