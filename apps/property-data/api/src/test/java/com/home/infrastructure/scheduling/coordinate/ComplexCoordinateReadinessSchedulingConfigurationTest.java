package com.home.infrastructure.scheduling.coordinate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.home.application.coordinate.readiness.ComplexCoordinateReadinessService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ComplexCoordinateReadinessSchedulingConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(ComplexCoordinateReadinessSchedulingConfiguration.class)
		.withPropertyValues("home.coordinate.readiness.enabled=true")
		.withBean(ComplexCoordinateReadinessService.class, () -> mock(ComplexCoordinateReadinessService.class));

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
}
