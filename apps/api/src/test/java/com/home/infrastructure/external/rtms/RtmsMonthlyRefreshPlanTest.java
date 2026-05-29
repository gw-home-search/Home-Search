package com.home.infrastructure.external.rtms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtmsMonthlyRefreshPlanTest {

	@Test
	@DisplayName("monthly refresh plan은 기준 월부터 lookback 월까지 page 1 요청을 deterministic하게 만든다")
	void monthlyRefreshPlanBuildsRequestsFromBaseMonthAndLookbackMonths() {
		RtmsMonthlyRefreshPlan plan = new RtmsMonthlyRefreshPlan(" 11680 ", " 202501 ", 2);

		assertThat(plan.monthlyRequests()).containsExactly(
			new RtmsApartmentTradeRequest("11680", "202501", 1),
			new RtmsApartmentTradeRequest("11680", "202412", 1),
			new RtmsApartmentTradeRequest("11680", "202411", 1)
		);
		assertThat(plan.dealYmds()).containsExactly("202501", "202412", "202411");
	}

	@Test
	@DisplayName("monthly refresh plan은 음수 또는 과도한 lookback을 거부한다")
	void monthlyRefreshPlanRejectsInvalidLookbackMonths() {
		assertThatThrownBy(() -> new RtmsMonthlyRefreshPlan("11680", "202501", -1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("lookbackMonths");
		assertThatThrownBy(() -> new RtmsMonthlyRefreshPlan("11680", "202501", 25))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("24");
	}
}
