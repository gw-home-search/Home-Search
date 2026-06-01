package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcParcelHardeningMigrationTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("V15 migration은 PNU prefix로 기존 parcel region_id와 address를 보강한다")
	void migrationBackfillsRegionAndAddressFromPnuPrefix() {
		migrateToVersion14();
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '11680103', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, pnu, latitude, longitude)
			VALUES (1001, '1168010300107770001', 37.5012345, 127.0543210)
			""").update();

		migrateToLatest();

		assertThat(jdbcClient.sql("""
			SELECT region_id, address
			FROM parcel
			WHERE id = 1001
			""")
			.query((resultSet, rowNumber) -> java.util.Map.of(
				"region_id", resultSet.getLong("region_id"),
				"address", resultSet.getString("address")
			))
			.single())
			.containsEntry("region_id", 1L)
			.containsEntry("address", "Sample-dong 777-1");
	}

	private void migrateToVersion14() {
		Flyway flyway = flyway(MigrationVersion.fromVersion("14"));
		flyway.clean();
		flyway.migrate();
		jdbcClient = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
	}

	private void migrateToLatest() {
		Flyway flyway = flyway(null);
		flyway.migrate();
		jdbcClient = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
	}

	private Flyway flyway(MigrationVersion target) {
		var configuration = Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/api")
			.schemas("public", "reference")
			.defaultSchema("public")
			.cleanDisabled(false);
		if (target != null) {
			configuration.target(target);
		}
		return configuration.load();
	}
}
