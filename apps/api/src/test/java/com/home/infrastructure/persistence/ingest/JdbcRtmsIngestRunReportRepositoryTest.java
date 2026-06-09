package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import com.home.application.ingest.trade.IngestResult;
import com.home.application.ingest.run.RtmsIngestRunRecord;
import com.home.application.ingest.run.RtmsIngestRunReport;
import com.home.application.ingest.run.RtmsIngestRunReportQuery;
import com.home.domain.ingest.run.RtmsIngestRunStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcRtmsIngestRunReportRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("RTMS 수집 실행 report는 월 범위와 status별 저장 결과를 집계하고 최근 run을 제한해 반환한다")
	void summarizesRunCountsByMonthRangeAndStatusWithRecentRuns() {
		JdbcRtmsIngestRunRepository runRepository = new JdbcRtmsIngestRunRepository(jdbcClient);
		JdbcRtmsIngestRunReportRepository reportRepository = new JdbcRtmsIngestRunReportRepository(jdbcClient);
		saveRuns(runRepository);
		RtmsIngestRunReportQuery query = new RtmsIngestRunReportQuery(
			"11680",
			"202511",
			"202512",
			List.of(RtmsIngestRunStatus.COMPLETED, RtmsIngestRunStatus.PARTIAL, RtmsIngestRunStatus.FAILED),
			2
		);

		RtmsIngestRunReport report = reportRepository.summarize(query);

		assertThat(report.totals().runCount()).isEqualTo(3);
		assertThat(report.totals().pageCount()).isEqualTo(3);
		assertThat(report.totals().read()).isEqualTo(7);
		assertThat(report.totals().rawSaved()).isEqualTo(7);
		assertThat(report.totals().normalizedInserted()).isEqualTo(2);
		assertThat(report.totals().duplicateSkipped()).isEqualTo(3);
		assertThat(report.totals().canceledSkipped()).isEqualTo(1);
		assertThat(report.totals().matchFailed()).isEqualTo(2);
		assertThat(report.totals().parseFailed()).isZero();
		assertThat(report.statusSummaries())
			.extracting(summary -> summary.status().name(), summary -> summary.runCount())
			.containsExactly(
				tuple("COMPLETED", 1L),
				tuple("PARTIAL", 1L),
				tuple("FAILED", 1L)
			);
		assertThat(report.recentRuns())
			.extracting(RtmsIngestRunRecord::dealYmd, RtmsIngestRunRecord::status)
			.containsExactly(
				tuple("202512", "FAILED"),
				tuple("202511", "PARTIAL")
			);
		assertThat(Arrays.stream(RtmsIngestRunReport.class.getRecordComponents())
			.map(component -> component.getName()))
			.doesNotContain("payload", "sourceKey");
	}

	@Test
	@DisplayName("RTMS 수집 실행 report는 status filter를 합계와 최근 run에 동일하게 적용한다")
	void appliesStatusFilterToTotalsAndRecentRuns() {
		JdbcRtmsIngestRunRepository runRepository = new JdbcRtmsIngestRunRepository(jdbcClient);
		JdbcRtmsIngestRunReportRepository reportRepository = new JdbcRtmsIngestRunReportRepository(jdbcClient);
		saveRuns(runRepository);
		RtmsIngestRunReportQuery query = new RtmsIngestRunReportQuery(
			"11680",
			"202511",
			"202512",
			List.of(RtmsIngestRunStatus.COMPLETED),
			10
		);

		RtmsIngestRunReport report = reportRepository.summarize(query);

		assertThat(report.totals().runCount()).isEqualTo(1);
		assertThat(report.totals().normalizedInserted()).isEqualTo(2);
		assertThat(report.statusSummaries())
			.singleElement()
			.satisfies(summary -> {
				assertThat(summary.status()).isEqualTo(RtmsIngestRunStatus.COMPLETED);
				assertThat(summary.runCount()).isEqualTo(1);
			});
		assertThat(report.recentRuns())
			.singleElement()
			.satisfies(run -> {
				assertThat(run.dealYmd()).isEqualTo("202512");
				assertThat(run.status()).isEqualTo("COMPLETED");
			});
	}

	@Test
	@DisplayName("RTMS 수집 실행 report는 조회 범위에 없는 status도 0건으로 반환한다")
	void returnsZeroCountsForStatusesWithoutRunsInRange() {
		JdbcRtmsIngestRunRepository runRepository = new JdbcRtmsIngestRunRepository(jdbcClient);
		JdbcRtmsIngestRunReportRepository reportRepository = new JdbcRtmsIngestRunReportRepository(jdbcClient);
		saveRuns(runRepository);

		RtmsIngestRunReport report = reportRepository.summarize(RtmsIngestRunReportQuery.between(
			"11110",
			"202512",
			"202512"
		));

		assertThat(report.totals().runCount()).isEqualTo(1);
		assertThat(report.statusSummaries())
			.extracting(summary -> summary.status().name(), summary -> summary.runCount())
			.containsExactly(
				tuple("COMPLETED", 1L),
				tuple("PARTIAL", 0L),
				tuple("FAILED", 0L)
			);
	}

	private void saveRuns(JdbcRtmsIngestRunRepository repository) {
		repository.save(RtmsIngestRunRecord.completed(
			"11680",
			"202512",
			2,
			new IngestResult(4, 4, 2, 1, 1, 1, 0),
			Instant.parse("2026-05-29T00:00:00Z"),
			Instant.parse("2026-05-29T00:00:05Z")
		));
		repository.save(RtmsIngestRunRecord.partiallyFailed(
			"11680",
			"202511",
			1,
			new IngestResult(3, 3, 0, 2, 0, 1, 0),
			"IllegalStateException: temporary 503",
			Instant.parse("2026-05-29T00:01:00Z"),
			Instant.parse("2026-05-29T00:01:05Z")
		));
		repository.save(RtmsIngestRunRecord.failed(
			"11680",
			"202512",
			0,
			IngestResult.empty(),
			"IllegalStateException: fetch failed",
			Instant.parse("2026-05-29T00:02:00Z"),
			Instant.parse("2026-05-29T00:02:05Z")
		));
		repository.save(RtmsIngestRunRecord.completed(
			"11680",
			"202410",
			1,
			new IngestResult(10, 10, 10, 0, 0, 0, 0),
			Instant.parse("2026-05-29T00:03:00Z"),
			Instant.parse("2026-05-29T00:03:05Z")
		));
		repository.save(RtmsIngestRunRecord.completed(
			"11110",
			"202512",
			1,
			new IngestResult(9, 9, 9, 0, 0, 0, 0),
			Instant.parse("2026-05-29T00:04:00Z"),
			Instant.parse("2026-05-29T00:04:05Z")
		));
	}
}
