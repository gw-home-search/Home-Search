package com.home.infrastructure.persistence.coordinate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import com.home.application.coordinate.ComplexCoordinateExceptionService;
import com.home.application.coordinate.ComplexCoordinateReadinessService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

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

	@Test
	@DisplayName("coordinate readiness scheduler는 scheduler.enabled=true일 때 주기 실행 bean으로 등록된다")
	void coordinateReadinessSchedulerIsRegisteredForPeriodicRefresh() {
		contextRunner
			.withPropertyValues("home.coordinate.readiness.scheduler.enabled=true")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasBean("complexCoordinateReadinessScheduler");
			});
	}

	@Test
	@DisplayName("coordinate readiness scheduler는 readiness만 켜도 기본 등록된다(default-on)")
	void coordinateReadinessSchedulerIsDefaultOnWhenReadinessEnabled() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasBean("complexCoordinateReadinessScheduler");
		});
	}

	@Test
	@DisplayName("coordinate readiness scheduler는 scheduler.enabled=false면 등록되지 않는다")
	void coordinateReadinessSchedulerCanBeDisabled() {
		contextRunner
			.withPropertyValues("home.coordinate.readiness.scheduler.enabled=false")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean("complexCoordinateReadinessScheduler");
			});
	}

	@Test
	@DisplayName("ODC identity strict block은 기본 설정에서 unavailable과 failed를 모두 차단한다")
	void odcloudIdentityStrictBlockIsDefaultOn() {
		contextRunner.run(context -> {
			ComplexCoordinateExceptionService service = context.getBean(ComplexCoordinateExceptionService.class);

			assertThat(ReflectionTestUtils.getField(service, "blockOnUnavailableIdentity")).isEqualTo(true);
			assertThat(ReflectionTestUtils.getField(service, "blockOnFailedIdentity")).isEqualTo(true);
		});
	}

	@Test
	@DisplayName("ODC identity strict block은 명시 property로 unavailable과 failed degrade를 허용할 수 있다")
	void odcloudIdentityStrictBlockCanBeExplicitlyDisabled() {
		contextRunner
			.withPropertyValues(
				"complex.coordinate.identity.block-on-unavailable=false",
				"complex.coordinate.identity.block-on-failed=false"
			)
			.run(context -> {
				ComplexCoordinateExceptionService service = context.getBean(ComplexCoordinateExceptionService.class);

				assertThat(ReflectionTestUtils.getField(service, "blockOnUnavailableIdentity")).isEqualTo(false);
				assertThat(ReflectionTestUtils.getField(service, "blockOnFailedIdentity")).isEqualTo(false);
			});
	}
}
