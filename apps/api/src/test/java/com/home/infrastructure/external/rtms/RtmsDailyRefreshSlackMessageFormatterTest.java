package com.home.infrastructure.external.rtms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.home.application.ingest.trade.IngestResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtmsDailyRefreshSlackMessageFormatterTest {

	private final RtmsDailyRefreshSlackMessageFormatter formatter = new RtmsDailyRefreshSlackMessageFormatter();

	@Test
	@DisplayName("daily refresh Slack summary는 신규 저장과 신규 없음 상태를 aggregate counter로 구분한다")
	void slackSummarySeparatesNewDataFromNoNewData() {
		String newDataMessage = formatter.format(new RtmsDailyRefreshExecution(List.of(
			RtmsDailyRefreshResult.from("11680", new RtmsMonthlyRefreshReport(List.of(
				RtmsMonthlyRefreshRunSummary.completed("11680", "202606", 1, new IngestResult(2, 2, 1, 1, 0, 0), 11L)
			)))
		)));
		String noNewDataMessage = formatter.format(new RtmsDailyRefreshExecution(List.of(
			RtmsDailyRefreshResult.from("11680", new RtmsMonthlyRefreshReport(List.of(
				RtmsMonthlyRefreshRunSummary.completed("11680", "202606", 1, new IngestResult(2, 2, 0, 2, 0, 0), 12L)
			)))
		)));

		assertThat(newDataMessage)
			.contains("신규 실거래 저장")
			.contains("normalizedInserted=1")
			.contains("status=COMPLETED");
		assertThat(noNewDataMessage)
			.contains("신규 저장 없음")
			.contains("normalizedInserted=0")
			.contains("duplicateSkipped=2");
	}

	@Test
	@DisplayName("daily refresh Slack summary는 부분 실패와 민감 query 값을 노출하지 않는다")
	void slackSummaryShowsPartialFailureWithoutSensitiveQueryValues() {
		String message = formatter.format(new RtmsDailyRefreshExecution(List.of(
			RtmsDailyRefreshResult.from("11680", new RtmsMonthlyRefreshReport(List.of(
				RtmsMonthlyRefreshRunSummary.completed("11680", "202606", 1, new IngestResult(2, 2, 1, 1, 0, 0), 11L),
				RtmsMonthlyRefreshRunSummary.partiallyFailed(
					"11680",
					"202605",
					1,
					new IngestResult(1, 1, 0, 1, 0, 0),
					"IllegalStateException: fetch failed serviceKey=PRIVATE_VALUE&service_key=PRIVATE_VALUE_2",
					12L
				)
			)))
		)));

		assertThat(message)
			.contains("부분 실패")
			.contains("status=PARTIAL")
			.contains("failureReason=IllegalStateException: fetch failed serviceKey=[REDACTED]&service_key=[REDACTED]")
			.doesNotContain("PRIVATE_VALUE")
			.doesNotContain("PRIVATE_VALUE_2")
			.doesNotContain("payload");
	}

	@Test
	@DisplayName("daily refresh Slack summary는 source key와 raw payload 값을 가린다")
	void slackSummaryRedactsSourceKeyAndRawPayloadValues() {
		String message = formatter.format(new RtmsDailyRefreshExecution(List.of(
			RtmsDailyRefreshResult.failed(
				new RtmsMonthlyRefreshPlan("11680", "202606", 0),
				new IllegalStateException("failed sourceKey=RTMS:PRIVATE_SOURCE_KEY payload={raw-private-payload}")
			)
		)));

		assertThat(message)
			.contains("sourceKey=[REDACTED]")
			.contains("payload=[REDACTED]")
			.doesNotContain("PRIVATE_SOURCE_KEY")
			.doesNotContain("raw-private-payload");
	}
}
