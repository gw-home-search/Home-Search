package com.home.rtmsloader;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class RtmsLoaderApplicationRunnerTest {

	@Test
	@DisplayName("RTMS loader application runner는 enabled 설정일 때 계획을 executor에 전달한다")
	void applicationRunnerExecutesPlannedJobWhenEnabled() throws Exception {
		RtmsLoaderProperties properties = new RtmsLoaderProperties();
		properties.setEnabled(true);
		properties.setMode("monthly-bulk");
		properties.setLawdCds(List.of("11680"));
		properties.setBaseDealYmd("202606");
		properties.setLookbackMonths(1);
		RecordingRtmsLoaderJobExecutor executor = new RecordingRtmsLoaderJobExecutor();
		RtmsLoaderApplicationRunner runner = new RtmsLoaderApplicationRunner(
			properties,
			new RtmsLoaderJobPlanner(Clock.fixed(Instant.parse("2026-06-17T15:00:00Z"), ZoneOffset.UTC)),
			executor
		);

		runner.run(new DefaultApplicationArguments());

		assertThat(executor.plans()).singleElement()
			.satisfies(plan -> assertThat(plan.months()).containsExactly(
				new RtmsLoaderMonthRequest("11680", "202606"),
				new RtmsLoaderMonthRequest("11680", "202605")
			));
	}

	@Test
	@DisplayName("RTMS loader application runner는 기본적으로 실행하지 않아 API daily runtime과 분리된다")
	void applicationRunnerSkipsByDefault() throws Exception {
		RecordingRtmsLoaderJobExecutor executor = new RecordingRtmsLoaderJobExecutor();
		RtmsLoaderApplicationRunner runner = new RtmsLoaderApplicationRunner(
			new RtmsLoaderProperties(),
			new RtmsLoaderJobPlanner(Clock.systemUTC()),
			executor
		);

		runner.run(new DefaultApplicationArguments());

		assertThat(executor.plans()).isEmpty();
	}

	private static final class RecordingRtmsLoaderJobExecutor implements RtmsLoaderJobExecutor {

		private final List<RtmsLoaderJobPlan> plans = new ArrayList<>();

		@Override
		public RtmsLoaderJobExecution execute(RtmsLoaderJobPlan plan) {
			plans.add(plan);
			return new RtmsLoaderJobExecution(plan.mode(), plan.months().size());
		}

		private List<RtmsLoaderJobPlan> plans() {
			return plans;
		}
	}
}
