package com.home.infrastructure.external.rtms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.home.application.ingest.backfill.RtmsBackfillChunkRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtmsNationwideBackfillPlanTest {

	@Test
	@DisplayName("전국 backfill plan은 lawdCd와 월 범위의 모든 chunk를 inclusive로 생성한다")
	void nationwideBackfillPlanBuildsInclusiveLawdMonthChunks() {
		RtmsNationwideBackfillPlan plan = new RtmsNationwideBackfillPlan(
			"rtms-national-201201-201203",
			List.of("11110", "11680"),
			"201201",
			"201203"
		);

		assertThat(plan.dealYmds()).containsExactly("201201", "201202", "201203");
		assertThat(plan.chunks()).containsExactly(
			new RtmsBackfillChunkRequest("11110", "201201"),
			new RtmsBackfillChunkRequest("11680", "201201"),
			new RtmsBackfillChunkRequest("11110", "201202"),
			new RtmsBackfillChunkRequest("11680", "201202"),
			new RtmsBackfillChunkRequest("11110", "201203"),
			new RtmsBackfillChunkRequest("11680", "201203")
		);
	}

	@Test
	@DisplayName("전국 backfill plan은 잘못된 월 범위나 lawdCd 입력을 거부한다")
	void nationwideBackfillPlanRejectsInvalidInput() {
		assertThatThrownBy(() -> new RtmsNationwideBackfillPlan(
			"rtms-national-invalid",
			List.of("11680"),
			"202606",
			"201201"
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("dealYmd");

		assertThatThrownBy(() -> new RtmsNationwideBackfillPlan(
			"rtms-national-invalid",
			List.of("1168010300"),
			"201201",
			"201201"
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("lawdCd");
	}
}
