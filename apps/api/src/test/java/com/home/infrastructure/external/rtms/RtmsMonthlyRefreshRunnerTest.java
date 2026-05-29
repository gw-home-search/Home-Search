package com.home.infrastructure.external.rtms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.home.application.ingest.IngestResult;
import com.home.application.ingest.OpenApiTradeIngestBatch;
import com.home.application.ingest.OpenApiTradeIngestService;
import com.home.application.ingest.RtmsIngestRunRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtmsMonthlyRefreshRunnerTest {

	@Test
	@DisplayName("monthly refresh는 같은 법정동/월을 page 1부터 재수집하고 duplicate/new insert summary를 남긴다")
	void monthlyRefreshStartsFromPageOneAndRecordsDuplicateAndNewInsertSummary() {
		RtmsApartmentTradeClient client = mock(RtmsApartmentTradeClient.class);
		OpenApiTradeIngestService ingestService = mock(OpenApiTradeIngestService.class);
		RecordingRtmsIngestRunRepository runRepository = new RecordingRtmsIngestRunRepository();
		RtmsMonthlyRefreshRunner runner = new RtmsMonthlyRefreshRunner(
			client,
			() -> ingestService,
			runRepository,
			Clock.fixed(Instant.parse("2026-05-29T00:00:00Z"), ZoneOffset.UTC)
		);
		RtmsApartmentTradeRequest pageOne = new RtmsApartmentTradeRequest("11680", "202512", 1);
		RtmsApartmentTradeRequest pageTwo = new RtmsApartmentTradeRequest("11680", "202512", 2);

		when(client.fetchPage(pageOne))
			.thenReturn(new RtmsApartmentTradePage(batch(1), 1, 1, 1))
			.thenReturn(new RtmsApartmentTradePage(batch(1), 1, 1, 2));
		when(client.fetchPage(pageTwo)).thenReturn(new RtmsApartmentTradePage(batch(2), 2, 1, 2));
		when(ingestService.ingest(any(OpenApiTradeIngestBatch.class)))
			.thenReturn(new IngestResult(1, 1, 1, 0, 0, 0))
			.thenReturn(new IngestResult(1, 1, 0, 1, 0, 0))
			.thenReturn(new IngestResult(1, 1, 1, 0, 0, 0));

		RtmsMonthlyRefreshRunSummary firstRun = runner.refresh("11680", "202512");
		RtmsMonthlyRefreshRunSummary secondRun = runner.refresh("11680", "202512");

		assertThat(firstRun).isEqualTo(new RtmsMonthlyRefreshRunSummary(
			"11680",
			"202512",
			1,
			1,
			1,
			0,
			0,
			0,
			1,
			RtmsMonthlyRefreshRunStatus.COMPLETED,
			null
		));
		assertThat(secondRun).isEqualTo(new RtmsMonthlyRefreshRunSummary(
			"11680",
			"202512",
			2,
			2,
			1,
			1,
			0,
			0,
			2,
			RtmsMonthlyRefreshRunStatus.COMPLETED,
			null
		));
		assertThat(secondRun.hasNewData()).isTrue();
		assertThat(runRepository.saved()).hasSize(2);
		assertThat(runRepository.saved().get(1)).satisfies(record -> {
			assertThat(record.lawdCd()).isEqualTo("11680");
			assertThat(record.dealYmd()).isEqualTo("202512");
			assertThat(record.status()).isEqualTo("COMPLETED");
			assertThat(record.pageCount()).isEqualTo(2);
			assertThat(record.normalizedInserted()).isEqualTo(1);
			assertThat(record.duplicateSkipped()).isEqualTo(1);
			assertThat(record.failureReason()).isNull();
		});
		verify(client, times(2)).fetchPage(pageOne);
		verify(client).fetchPage(pageTwo);
	}

	@Test
	@DisplayName("monthly refresh runner는 plan의 여러 월을 순서대로 실행하고 aggregate report를 반환한다")
	void monthlyRefreshRunnerExecutesPlanMonthsAndReturnsAggregateReport() {
		RtmsApartmentTradeClient client = mock(RtmsApartmentTradeClient.class);
		OpenApiTradeIngestService ingestService = mock(OpenApiTradeIngestService.class);
		RtmsMonthlyRefreshRunner runner = new RtmsMonthlyRefreshRunner(client, ingestService);
		RtmsApartmentTradeRequest currentMonth = new RtmsApartmentTradeRequest("11680", "202501", 1);
		RtmsApartmentTradeRequest previousMonth = new RtmsApartmentTradeRequest("11680", "202412", 1);
		when(client.fetchPage(currentMonth)).thenReturn(RtmsApartmentTradePage.single(batch("202501", 1)));
		when(client.fetchPage(previousMonth)).thenReturn(RtmsApartmentTradePage.single(batch("202412", 1)));
		when(ingestService.ingest(any(OpenApiTradeIngestBatch.class)))
			.thenReturn(new IngestResult(1, 1, 1, 0, 0, 0))
			.thenReturn(new IngestResult(2, 2, 0, 2, 0, 0));

		RtmsMonthlyRefreshReport report = runner.refresh(new RtmsMonthlyRefreshPlan("11680", "202501", 1));

		assertThat(report.runs()).hasSize(2);
		assertThat(report.totalResult()).isEqualTo(new IngestResult(3, 3, 1, 2, 0, 0));
		assertThat(report.totalPageCount()).isEqualTo(2);
		assertThat(report.hasNewData()).isTrue();
		verify(client).fetchPage(currentMonth);
		verify(client).fetchPage(previousMonth);
	}

	@Test
	@DisplayName("monthly refresh runner는 한 월의 fetch 실패를 FAILED run으로 저장하고 다음 월을 계속 실행한다")
	void monthlyRefreshRunnerRecordsFailedMonthAndContinuesNextMonth() {
		RtmsApartmentTradeClient client = mock(RtmsApartmentTradeClient.class);
		OpenApiTradeIngestService ingestService = mock(OpenApiTradeIngestService.class);
		RecordingRtmsIngestRunRepository runRepository = new RecordingRtmsIngestRunRepository();
		RtmsMonthlyRefreshRunner runner = new RtmsMonthlyRefreshRunner(
			client,
			() -> ingestService,
			runRepository,
			Clock.fixed(Instant.parse("2026-05-29T00:00:00Z"), ZoneOffset.UTC)
		);
		RtmsApartmentTradeRequest currentMonth = new RtmsApartmentTradeRequest("11680", "202501", 1);
		RtmsApartmentTradeRequest previousMonth = new RtmsApartmentTradeRequest("11680", "202412", 1);
		when(client.fetchPage(currentMonth))
			.thenThrow(new IllegalStateException("fetch failed serviceKey=sample-value"));
		when(client.fetchPage(previousMonth)).thenReturn(RtmsApartmentTradePage.single(batch("202412", 1)));
		when(ingestService.ingest(any(OpenApiTradeIngestBatch.class)))
			.thenReturn(new IngestResult(2, 2, 1, 1, 0, 0));

		RtmsMonthlyRefreshReport report = runner.refresh(new RtmsMonthlyRefreshPlan("11680", "202501", 1));

		assertThat(report.runs()).hasSize(2);
		assertThat(report.runs().get(0)).satisfies(run -> {
			assertThat(run.dealYmd()).isEqualTo("202501");
			assertThat(run.status()).isEqualTo(RtmsMonthlyRefreshRunStatus.FAILED);
			assertThat(run.failureReason())
				.contains("IllegalStateException")
				.contains("serviceKey=[REDACTED]")
				.doesNotContain("sample-value");
		});
		assertThat(report.runs().get(1)).satisfies(run -> {
			assertThat(run.dealYmd()).isEqualTo("202412");
			assertThat(run.status()).isEqualTo(RtmsMonthlyRefreshRunStatus.COMPLETED);
			assertThat(run.normalizedInserted()).isEqualTo(1);
		});
		assertThat(report.totalResult()).isEqualTo(new IngestResult(2, 2, 1, 1, 0, 0));
		assertThat(runRepository.saved()).hasSize(2);
		assertThat(runRepository.saved().get(0)).satisfies(record -> {
			assertThat(record.status()).isEqualTo("FAILED");
			assertThat(record.dealYmd()).isEqualTo("202501");
			assertThat(record.pageCount()).isZero();
			assertThat(record.failureReason()).contains("serviceKey=[REDACTED]");
		});
		assertThat(runRepository.saved().get(1).status()).isEqualTo("COMPLETED");
		verify(client, times(3)).fetchPage(currentMonth);
		verify(client).fetchPage(previousMonth);
	}

	@Test
	@DisplayName("monthly refresh runner는 일시적 fetch 실패를 retry하고 성공한 run으로 저장한다")
	void monthlyRefreshRunnerRetriesTransientFetchFailure() {
		RtmsApartmentTradeClient client = mock(RtmsApartmentTradeClient.class);
		OpenApiTradeIngestService ingestService = mock(OpenApiTradeIngestService.class);
		RecordingRtmsIngestRunRepository runRepository = new RecordingRtmsIngestRunRepository();
		RtmsMonthlyRefreshRunner runner = new RtmsMonthlyRefreshRunner(
			client,
			() -> ingestService,
			runRepository,
			Clock.fixed(Instant.parse("2026-05-29T00:00:00Z"), ZoneOffset.UTC)
		);
		RtmsApartmentTradeRequest request = new RtmsApartmentTradeRequest("11680", "202501", 1);
		when(client.fetchPage(request))
			.thenThrow(new IllegalStateException("temporary 503"))
			.thenReturn(RtmsApartmentTradePage.single(batch("202501", 1)));
		when(ingestService.ingest(any(OpenApiTradeIngestBatch.class)))
			.thenReturn(new IngestResult(1, 1, 1, 0, 0, 0));

		RtmsMonthlyRefreshRunSummary summary = runner.refresh("11680", "202501");

		assertThat(summary.status()).isEqualTo(RtmsMonthlyRefreshRunStatus.COMPLETED);
		assertThat(summary.failureReason()).isNull();
		assertThat(runRepository.saved()).singleElement()
			.satisfies(record -> assertThat(record.status()).isEqualTo("COMPLETED"));
		verify(client, times(2)).fetchPage(request);
	}

	@Test
	@DisplayName("monthly refresh runner는 일부 page ingest 후 fetch 실패를 PARTIAL run으로 저장한다")
	void monthlyRefreshRunnerRecordsPartialRunAfterSomePagesWereIngested() {
		RtmsApartmentTradeClient client = mock(RtmsApartmentTradeClient.class);
		OpenApiTradeIngestService ingestService = mock(OpenApiTradeIngestService.class);
		RecordingRtmsIngestRunRepository runRepository = new RecordingRtmsIngestRunRepository();
		RtmsMonthlyRefreshRunner runner = new RtmsMonthlyRefreshRunner(
			client,
			() -> ingestService,
			runRepository,
			Clock.fixed(Instant.parse("2026-05-29T00:00:00Z"), ZoneOffset.UTC)
		);
		RtmsApartmentTradeRequest pageOne = new RtmsApartmentTradeRequest("11680", "202501", 1);
		RtmsApartmentTradeRequest pageTwo = new RtmsApartmentTradeRequest("11680", "202501", 2);
		when(client.fetchPage(pageOne)).thenReturn(new RtmsApartmentTradePage(batch("202501", 1), 1, 1, 2));
		when(client.fetchPage(pageTwo)).thenThrow(new IllegalStateException("temporary 503 serviceKey=sample-value"));
		when(ingestService.ingest(any(OpenApiTradeIngestBatch.class)))
			.thenReturn(new IngestResult(1, 1, 1, 0, 0, 0));

		RtmsMonthlyRefreshRunSummary summary = runner.refresh("11680", "202501");

		assertThat(summary.status()).isEqualTo(RtmsMonthlyRefreshRunStatus.PARTIAL);
		assertThat(summary.pageCount()).isEqualTo(1);
		assertThat(summary.normalizedInserted()).isEqualTo(1);
		assertThat(summary.failureReason())
			.contains("IllegalStateException")
			.contains("serviceKey=[REDACTED]");
		assertThat(runRepository.saved()).singleElement()
			.satisfies(record -> {
				assertThat(record.status()).isEqualTo("PARTIAL");
				assertThat(record.pageCount()).isEqualTo(1);
				assertThat(record.normalizedInserted()).isEqualTo(1);
				assertThat(record.failureReason()).contains("serviceKey=[REDACTED]");
			});
		verify(client).fetchPage(pageOne);
		verify(client, times(3)).fetchPage(pageTwo);
	}

	@Test
	@DisplayName("monthly refresh는 ingest service가 없으면 live fetch 전에 실패한다")
	void monthlyRefreshFailsBeforeLiveFetchWhenIngestServiceIsUnavailable() {
		RtmsApartmentTradeClient client = mock(RtmsApartmentTradeClient.class);
		RtmsMonthlyRefreshRunner runner = new RtmsMonthlyRefreshRunner(
			client,
			() -> {
				throw new IllegalStateException("OpenApiTradeIngestService is required");
			}
		);

		assertThatThrownBy(() -> runner.refresh("11680", "202512"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("OpenApiTradeIngestService");
		verifyNoInteractions(client);
	}

	private OpenApiTradeIngestBatch batch(int pageNo) {
		return new OpenApiTradeIngestBatch("RTMS", "11680", "202512", pageNo, List.of());
	}

	private OpenApiTradeIngestBatch batch(String dealYmd, int pageNo) {
		return new OpenApiTradeIngestBatch("RTMS", "11680", dealYmd, pageNo, List.of());
	}

	private static final class RecordingRtmsIngestRunRepository
		implements com.home.application.ingest.RtmsIngestRunRepository {

		private final List<RtmsIngestRunRecord> saved = new ArrayList<>();

		@Override
		public RtmsIngestRunRecord save(RtmsIngestRunRecord record) {
			saved.add(record);
			return record;
		}

		private List<RtmsIngestRunRecord> saved() {
			return saved;
		}
	}
}
