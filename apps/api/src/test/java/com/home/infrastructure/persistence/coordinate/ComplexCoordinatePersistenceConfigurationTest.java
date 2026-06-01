package com.home.infrastructure.persistence.coordinate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import com.home.application.coordinate.ComplexCoordinateReadinessService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ComplexCoordinatePersistenceConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(
			JdbcTemplateAutoConfiguration.class,
			JdbcClientAutoConfiguration.class
		))
		.withUserConfiguration(ComplexCoordinatePersistenceConfiguration.class)
		.withPropertyValues("home.coordinate.readiness.enabled=true")
		.withBean(DataSource.class, () -> mock(DataSource.class));

	@Test
	@DisplayName("coordinate readiness runner는 JdbcClient auto-config 환경에서 시작된다")
	void coordinateReadinessRunnerStartsWithJdbcClientAutoConfiguration() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(ComplexCoordinateReadinessService.class);
			assertThat(context).hasSingleBean(ComplexCoordinateReadinessRunner.class);
		});
	}
}
