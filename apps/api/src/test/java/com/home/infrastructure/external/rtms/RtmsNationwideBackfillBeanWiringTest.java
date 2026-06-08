package com.home.infrastructure.external.rtms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import com.home.application.ingest.RtmsBackfillChunkRepository;
import com.home.application.ingest.RtmsBackfillJobRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

class RtmsNationwideBackfillBeanWiringTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(
			JacksonAutoConfiguration.class,
			JdbcTemplateAutoConfiguration.class,
			JdbcClientAutoConfiguration.class
		))
		.withInitializer(context -> registerRtmsExternalThenIngestPersistence((GenericApplicationContext) context))
		.withPropertyValues(
			"spring.flyway.enabled=false",
			"home.trade.partition.maintenance.enabled=false",
			"home.ingest.raw-reconcile.enabled=false"
		)
		.withBean(DataSource.class, () -> mock(DataSource.class));

	@Test
	@DisplayName("nationwide backfill runner는 JdbcClient auto-config 환경에서 backfill repository와 함께 등록된다")
	void nationwideBackfillRunnerIsRegisteredWithJdbcClientAutoConfiguration() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(JdbcClient.class);
			assertThat(context).hasSingleBean(RtmsBackfillJobRepository.class);
			assertThat(context).hasSingleBean(RtmsBackfillChunkRepository.class);
			assertThat(context).hasSingleBean(RtmsNationwideBackfillRunner.class);
		});
	}

	@SuppressWarnings("unchecked")
	private static void registerRtmsExternalThenIngestPersistence(GenericApplicationContext context) {
		try {
			AnnotatedBeanDefinitionReader reader = new AnnotatedBeanDefinitionReader(context);
			reader.register(RtmsExternalApiConfiguration.class);
			reader.register((Class<Object>) Class.forName(
				"com.home.infrastructure.persistence.ingest.IngestPersistenceConfiguration"
			));
		}
		catch (ClassNotFoundException ex) {
			throw new IllegalStateException("IngestPersistenceConfiguration is required for RTMS wiring tests", ex);
		}
	}
}
