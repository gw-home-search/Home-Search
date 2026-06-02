package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import com.home.application.ingest.RtmsIngestRunRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
}
