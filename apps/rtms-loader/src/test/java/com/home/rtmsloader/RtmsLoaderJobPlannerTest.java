package com.home.rtmsloader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtmsLoaderJobPlannerTest {

	private static final Clock JUNE_2026_CLOCK = Clock.fixed(
		Instant.parse("2026-06-17T15:00:00Z"),
		ZoneOffset.UTC
	);

	private final RtmsLoaderJobPlanner planner = new RtmsLoaderJobPlanner(JUNE_2026_CLOCK);

	@Test
	@DisplayName("RTMS loader monthly-bulk plan은 여러 법정동과 lookback 월을 실행 요청으로 확장한다")
	void monthlyBulkPlanExpandsLawdCodesAndLookbackMonths() {
		RtmsLoaderJobPlan plan = planner.plan(new RtmsLoaderJobRequest(
			RtmsLoaderMode.MONTHLY_BULK,
			List.of("11680", "11710"),
			"202606",
			1
		));

		assertThat(plan.mode()).isEqualTo(RtmsLoaderMode.MONTHLY_BULK);
		assertThat(plan.months()).containsExactly(
			new RtmsLoaderMonthRequest("11680", "202606"),
			new RtmsLoaderMonthRequest("11680", "202605"),
			new RtmsLoaderMonthRequest("11710", "202606"),
			new RtmsLoaderMonthRequest("11710", "202605")
		);
	}

	@Test
	@DisplayName("RTMS loader initial-load plan은 명시 월 없이 현재 KST 월을 기본값으로 사용한다")
	void initialLoadPlanDefaultsToCurrentKstMonth() {
		RtmsLoaderJobPlan plan = planner.plan(new RtmsLoaderJobRequest(
			RtmsLoaderMode.INITIAL_LOAD,
			List.of("11680"),
			null,
			0
		));

		assertThat(plan.mode()).isEqualTo(RtmsLoaderMode.INITIAL_LOAD);
		assertThat(plan.months()).containsExactly(new RtmsLoaderMonthRequest("11680", "202606"));
	}

	@Test
	@DisplayName("RTMS loader plan은 법정동 코드와 거래월을 core validation으로 검증한다")
	void planRejectsInvalidBatchIdentity() {
		assertThatThrownBy(() -> planner.plan(new RtmsLoaderJobRequest(
				RtmsLoaderMode.MONTHLY_BULK,
				List.of("invalid"),
				"202606",
				0
			)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("lawdCd");

		assertThatThrownBy(() -> planner.plan(new RtmsLoaderJobRequest(
				RtmsLoaderMode.MONTHLY_BULK,
				List.of("11680"),
				"202613",
				0
			)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("dealYmd");
	}
}
