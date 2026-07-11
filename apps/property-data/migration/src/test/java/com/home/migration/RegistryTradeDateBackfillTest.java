package com.home.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class RegistryTradeDateBackfillTest {

	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
		DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
	);

	private static DriverManagerDataSource dataSource;

	@BeforeAll
	static void startPostgres() {
		POSTGRES.start();
		dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		dataSource.setDriverClassName(POSTGRES.getDriverClassName());
	}

	@AfterEach
	void clean() {
		flyway(null, false).clean();
	}

	@Test
	@DisplayName("registry trade date backfill은 V5 legacy pair를 bounded commit하고 재실행 가능하게 만든다")
	void backfillsLegacyRegistryPairAndAllowsV6Validation() {
		Flyway throughV4 = flyway(MigrationVersion.fromVersion("4"), false);
		throughV4.clean();
		throughV4.migrate();
		JdbcClient jdbc = JdbcClient.create(dataSource);
		seedLegacyLinkedRegistry(jdbc);
		flyway(MigrationVersion.fromVersion("5"), true).migrate();
		RegistryTradeDateBackfill backfill = new RegistryTradeDateBackfill(
			jdbc,
			new TransactionTemplate(new DataSourceTransactionManager(dataSource))
		);

		RegistryTradeDateBackfill.Result first = backfill.execute(1, 0);
		RegistryTradeDateBackfill.Result second = backfill.execute(1, 0);

		assertThat(first.updated()).isEqualTo(1);
		assertThat(first.batches()).isEqualTo(1);
		assertThat(second.updated()).isZero();
		assertThat(jdbc.sql("SELECT trade_deal_date FROM trade_source_key_registry WHERE source_key = 'legacy-key'")
			.query(LocalDate.class)
			.single()).isEqualTo(LocalDate.of(2025, 12, 15));
		assertThat(flyway(MigrationVersion.fromVersion("6"), true).migrate().migrationsExecuted).isEqualTo(1);
	}

	private static Flyway flyway(MigrationVersion target, boolean cleanDisabled) {
		var configuration = Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/api")
			.schemas("public", "reference", "batch")
			.defaultSchema("public")
			.cleanDisabled(cleanDisabled);
		if (target != null) {
			configuration.target(target);
		}
		return configuration.load();
	}

	private void seedLegacyLinkedRegistry(JdbcClient jdbc) {
		jdbc.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (990001, '990001', 'Backfill region', 'eup-myeon-dong')
			""").update();
		jdbc.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (990001, 990001, '9900010100100010001', 'Backfill address', 37.5, 127.0)
			""").update();
		jdbc.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name)
			VALUES (990001, 990001, 'BACKFILL-PK', 'BACKFILL-APT', 'Backfill complex')
			""").update();
		jdbc.sql("""
			INSERT INTO raw_trade_ingest (
			  id, source, source_key, lawd_cd, deal_ymd, page_no, payload, payload_hash, status
			)
			VALUES (990001, 'RTMS', 'legacy-key', '11680', '202512', 1, '{}', 'legacy-hash', 'NORMALIZED')
			""").update();
		jdbc.sql("""
			INSERT INTO trade (
			  id, complex_id, deal_date, deal_amount, source, source_key, complex_pk, apt_seq, raw_ingest_id
			)
			VALUES (990001, 990001, DATE '2025-12-15', 125000, 'RTMS', 'legacy-key',
			        'BACKFILL-PK', 'BACKFILL-APT', 990001)
			""").update();
		jdbc.sql("""
			INSERT INTO trade_source_key_registry (id, source, source_key, raw_ingest_id, trade_id)
			VALUES (990001, 'RTMS', 'legacy-key', 990001, 990001)
			""").update();
	}
}
