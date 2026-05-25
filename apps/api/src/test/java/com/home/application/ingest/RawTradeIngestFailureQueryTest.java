package com.home.application.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RawTradeIngestFailureQueryTest {

	@Test
	@DisplayName("failure query는 filter를 trim하고 inspection status를 기본값으로 사용한다")
	void trimsFiltersAndDefaultsToInspectionStatuses() {
		RawTradeIngestFailureQuery query = RawTradeIngestFailureQuery.between(
			" RTMS ",
			" 11680 ",
			"202512",
			"202512"
		);

		assertThat(query.source()).isEqualTo("RTMS");
		assertThat(query.lawdCd()).isEqualTo("11680");
		assertThat(query.statuses()).containsExactly(
			RawTradeIngestStatus.MATCH_FAILED,
			RawTradeIngestStatus.PARSE_FAILED,
			RawTradeIngestStatus.DUPLICATE
		);
		assertThat(query.statusNames()).containsExactly("MATCH_FAILED", "PARSE_FAILED", "DUPLICATE");
	}

	@Test
	@DisplayName("failure query는 inspection이 아닌 status와 잘못된 deal month 범위를 거부한다")
	void rejectsUnsafeInspectionConditions() {
		assertThatThrownBy(() -> new RawTradeIngestFailureQuery(
			"RTMS",
			"11680",
			"202512",
			"202512",
			List.of(RawTradeIngestStatus.NORMALIZED)
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("MATCH_FAILED");

		assertThatThrownBy(() -> RawTradeIngestFailureQuery.between("RTMS", "11680", "2025-12", "202512"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("yyyyMM");

		assertThatThrownBy(() -> RawTradeIngestFailureQuery.between("RTMS", "11680", "202512", "202511"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("before or equal");
	}
}
