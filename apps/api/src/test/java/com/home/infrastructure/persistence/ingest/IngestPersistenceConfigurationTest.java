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
}
