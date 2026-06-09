package com.home.application.ingest.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParsedRtmsTradeTest {

	@Test
	@DisplayName("RTMS trade item parser는 거래일, 금액, 0층을 normalized command 값으로 변환한다")
	void parsesTradeItemIntoNormalizedCommandFields() {
		ParsedRtmsTrade parsed = ParsedRtmsTrade.from(item("125,000", 0));

		assertThat(parsed.dealDate()).isEqualTo(LocalDate.of(2025, 12, 1));
		assertThat(parsed.dealAmount()).isEqualTo(125000L);
		assertThat(parsed.floor()).isNull();
	}

	@Test
	@DisplayName("RTMS trade item parser는 유효하지 않은 금액 message를 유지한다")
	void keepsInvalidAmountFailureMessage() {
		assertThatThrownBy(() -> ParsedRtmsTrade.from(item("not-a-number", 12)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("dealAmount must be numeric");
	}

	private OpenApiTradeItem item(String dealAmount, Integer floor) {
		return new OpenApiTradeItem(
			"101",
			"Sample Apartment",
			"APT-501",
			dealAmount,
			1,
			12,
			2025,
			84.93,
			floor,
			"140-1",
			"11680",
			"10300",
			"{}"
		);
	}
}
