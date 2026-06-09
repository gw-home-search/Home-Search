package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import com.home.application.coordinate.lookup.ParcelCoordinateSourceRepository;
import com.home.application.ingest.run.RtmsIngestRunRepository;
import com.home.infrastructure.persistence.ingest.coordinate.CoordinateSourceDbProperties;
import com.home.infrastructure.persistence.ingest.coordinate.JdbcCoordinateSourceParcelCoordinateRepository;
import com.home.infrastructure.persistence.ingest.raw.RawIngestReconciliationRunner;
import com.home.infrastructure.persistence.ingest.run.JdbcRtmsIngestRunRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;

class IngestPersistenceConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(
			JdbcTemplateAutoConfiguration.class,
			JdbcClientAutoConfiguration.class
		))
		.withUserConfiguration(IngestPersistenceConfiguration.class)
		.withPropertyValues("home.trade.partition.maintenance.enabled=false")
		.withBean(DataSource.class, () -> mock(DataSource.class));

	@Test
	@DisplayName("RTMS monthly refresh run repository는 JdbcClient auto-config 환경에서 등록된다")
	void rtmsIngestRunRepositoryIsRegisteredWithJdbcClientAutoConfiguration() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(RtmsIngestRunRepository.class);
			assertThat(context.getBean(RtmsIngestRunRepository.class)).isInstanceOf(JdbcRtmsIngestRunRepository.class);
		});
	}

	@Test
	@DisplayName("Coordinate Source DB URL이 없으면 coordinate source lookup은 empty repository로 남는다")
	void coordinateSourceRepositoryIsEmptyWithoutSourceDbUrl() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(ParcelCoordinateSourceRepository.class);
			assertThat(context.getBean(ParcelCoordinateSourceRepository.class).findByPnu("1168010300107770001"))
				.isEmpty();
		});
	}

	@Test
	@DisplayName("Coordinate Source DB URL이 있으면 전용 source lookup repository를 등록한다")
	void coordinateSourceRepositoryUsesDedicatedSourceDbWhenUrlConfigured() {
		contextRunner
			.withPropertyValues(
				"home.coordinate-source.db.jdbc-url=jdbc:postgresql://localhost:15432/home_search_coordinate_source",
				"home.coordinate-source.db.username=home_search",
				"home.coordinate-source.db.password=home_search_local_password"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(ParcelCoordinateSourceRepository.class);
				assertThat(context.getBean(ParcelCoordinateSourceRepository.class))
					.isInstanceOf(JdbcCoordinateSourceParcelCoordinateRepository.class);
			});
	}

	@Test
	@DisplayName("Coordinate Source DB connection은 read-only와 짧은 timeout option을 사용한다")
	void coordinateSourcePropertiesBuildReadOnlyTimeoutConnectionOptions() {
		CoordinateSourceDbProperties properties = new CoordinateSourceDbProperties(
			"jdbc:postgresql://localhost:15432/home_search_coordinate_source",
			"home_search",
			"home_search_local_password",
			5,
			10,
			1000,
			3000,
			true
		);

		java.util.Properties connectionProperties = properties.connectionProperties();

		assertThat(connectionProperties.getProperty("connectTimeout")).isEqualTo("5");
		assertThat(connectionProperties.getProperty("socketTimeout")).isEqualTo("10");
		assertThat(connectionProperties.getProperty("readOnly")).isEqualTo("true");
		assertThat(connectionProperties.getProperty("options"))
			.isEqualTo("-c lock_timeout=1000 -c statement_timeout=3000");
	}

	@Test
	@DisplayName("raw reconciliation runner는 JDBC 환경에서 기본 등록되고 명시적으로 끌 수 있다")
	void rawReconciliationRunnerIsEnabledByDefaultInJdbcContext() {
		contextRunner
			.withBean(JdbcClient.class, () -> mock(JdbcClient.class))
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(RawIngestReconciliationRunner.class);
			});

		contextRunner
			.withBean(JdbcClient.class, () -> mock(JdbcClient.class))
			.withPropertyValues("home.ingest.raw-reconcile.enabled=false")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(RawIngestReconciliationRunner.class);
			});
	}
}
