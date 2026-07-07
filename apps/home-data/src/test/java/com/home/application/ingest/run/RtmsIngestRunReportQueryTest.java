package com.home.application.ingest.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.home.domain.ingest.run.RtmsIngestRunStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtmsIngestRunReportQueryTest {

	@Test
	@DisplayName("RTMS run report query는 filter를 trim하고 모든 run status를 기본값으로 사용한다")
	void trimsFiltersAndDefaultsToAllRunStatuses() {
		RtmsIngestRunReportQuery query = RtmsIngestRunReportQuery.between(
			" 11680 ",
			"202511",
			"202512"
		);

		assertThat(query.lawdCd()).isEqualTo("11680");
		assertThat(query.dealYmdFrom()).isEqualTo("202511");
		assertThat(query.dealYmdTo()).isEqualTo("202512");
		assertThat(query.statuses()).containsExactly(
			RtmsIngestRunStatus.COMPLETED,
			RtmsIngestRunStatus.PARTIAL,
			RtmsIngestRunStatus.FAILED
		);
		assertThat(query.statusNames()).containsExactly("COMPLETED", "PARTIAL", "FAILED");
		assertThat(query.recentRunLimit()).isEqualTo(10);
	}

	@Test
	@DisplayName("RTMS run report query는 잘못된 deal month, 역전 범위, 잘못된 recent limit을 거부한다")
	void rejectsUnsafeReportConditions() {
		assertThatThrownBy(() -> RtmsIngestRunReportQuery.between("11680", "2025-11", "202512"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("yyyyMM");

		assertThatThrownBy(() -> RtmsIngestRunReportQuery.between("11680", "202512", "202511"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("before or equal");

		assertThatThrownBy(() -> new RtmsIngestRunReportQuery(
			"11680",
			"202511",
			"202512",
			List.of(RtmsIngestRunStatus.COMPLETED),
			0
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("recentRunLimit");
	}
}
