package com.home.infrastructure.external.rtms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import com.home.application.ingest.trade.IngestResult;
import com.home.application.ingest.trade.OpenApiTradeIngestBatch;
import com.home.application.ingest.trade.OpenApiTradeIngestService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import com.home.application.ingest.backfill.RtmsBackfillChunkStatusCounts;
import com.home.domain.ingest.backfill.RtmsBackfillJobStatus;

@ExtendWith(OutputCaptureExtension.class)
class RtmsOneShotTradeIngestRunnerTest {

	@Test
	@DisplayName("one-shot runner는 RTMS batch를 fetch하고 OpenApiTradeIngestService에 위임한다")
	void runnerDelegatesFetchedBatchToIngestService() {
		RtmsApartmentTradeClient client = mock(RtmsApartmentTradeClient.class);
		OpenApiTradeIngestService ingestService = mock(OpenApiTradeIngestService.class);
		RtmsOneShotTradeIngestRunner runner = new RtmsOneShotTradeIngestRunner(client, ingestService);
		RtmsApartmentTradeRequest request = new RtmsApartmentTradeRequest("11680", "202512", 3);
		OpenApiTradeIngestBatch batch = new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			3,
			List.of()
		);
		IngestResult expected = new IngestResult(0, 0, 0, 0, 0, 0);
		when(client.fetchPage(request)).thenReturn(RtmsApartmentTradePage.single(batch));
		when(ingestService.ingest(batch)).thenReturn(expected);

		IngestResult result = runner.ingest(request);

		assertThat(result).isEqualTo(expected);
		verify(client).fetchPage(request);
		verify(ingestService).ingest(batch);
	}

	@Test
	@DisplayName("one-shot runner는 RTMS page metadata에 따라 모든 page를 수집하고 ingest result를 누적한다")
	void runnerFetchesAllPagesAndAggregatesIngestResults() {
		RtmsApartmentTradeClient client = mock(RtmsApartmentTradeClient.class);
		OpenApiTradeIngestService ingestService = mock(OpenApiTradeIngestService.class);
		RtmsOneShotTradeIngestRunner runner = new RtmsOneShotTradeIngestRunner(client, ingestService);
		RtmsApartmentTradeRequest firstRequest = new RtmsApartmentTradeRequest("11680", "202512", 1);
		RtmsApartmentTradeRequest secondRequest = new RtmsApartmentTradeRequest("11680", "202512", 2);
		RtmsApartmentTradeRequest thirdRequest = new RtmsApartmentTradeRequest("11680", "202512", 3);
		OpenApiTradeIngestBatch firstBatch = new OpenApiTradeIngestBatch("RTMS", "11680", "202512", 1, List.of());
		OpenApiTradeIngestBatch secondBatch = new OpenApiTradeIngestBatch("RTMS", "11680", "202512", 2, List.of());
		OpenApiTradeIngestBatch thirdBatch = new OpenApiTradeIngestBatch("RTMS", "11680", "202512", 3, List.of());
		when(client.fetchPage(firstRequest)).thenReturn(new RtmsApartmentTradePage(firstBatch, 1, 100, 250));
		when(client.fetchPage(secondRequest)).thenReturn(new RtmsApartmentTradePage(secondBatch, 2, 100, 250));
		when(client.fetchPage(thirdRequest)).thenReturn(new RtmsApartmentTradePage(thirdBatch, 3, 100, 250));
		when(ingestService.ingest(firstBatch)).thenReturn(new IngestResult(100, 100, 80, 10, 5, 5));
		when(ingestService.ingest(secondBatch)).thenReturn(new IngestResult(100, 100, 90, 5, 4, 1));
		when(ingestService.ingest(thirdBatch)).thenReturn(new IngestResult(50, 50, 45, 2, 2, 1));

		IngestResult result = runner.ingest(firstRequest);

		assertThat(result).isEqualTo(new IngestResult(250, 250, 215, 17, 11, 7));
		verify(client).fetchPage(firstRequest);
		verify(client).fetchPage(secondRequest);
		verify(client).fetchPage(thirdRequest);
		verify(ingestService).ingest(firstBatch);
		verify(ingestService).ingest(secondBatch);
		verify(ingestService).ingest(thirdBatch);
	}

	@Test
	@DisplayName("one-shot runner는 ingest service가 없으면 live fetch 전에 실패한다")
	void runnerFailsBeforeLiveFetchWhenIngestServiceIsUnavailable() {
		RtmsApartmentTradeClient client = mock(RtmsApartmentTradeClient.class);
		RtmsOneShotTradeIngestRunner runner = new RtmsOneShotTradeIngestRunner(
			client,
			() -> {
				throw new IllegalStateException("OpenApiTradeIngestService is required");
			}
		);

		assertThatThrownBy(() -> runner.ingest(new RtmsApartmentTradeRequest("11680", "202512", 1)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("OpenApiTradeIngestService");
		verifyNoInteractions(client);
	}

	@Test
	@DisplayName("disabled local trigger는 RTMS one-shot runner를 호출하지 않는다")
	void disabledLocalTriggerDoesNotCallRunner() throws Exception {
		RtmsOneShotTradeIngestRunner runner = mock(RtmsOneShotTradeIngestRunner.class);
		RtmsOneShotIngestApplicationRunner applicationRunner = new RtmsOneShotIngestApplicationRunner(
			runner,
			new RtmsOneShotIngestProperties(false, null, null, null, false),
			rtmsProperties("DUMMY")
		);

		applicationRunner.run(new DefaultApplicationArguments());

		verifyNoInteractions(runner);
	}

	@Test
	@DisplayName("RTMS one-shot application runner는 coordinate readiness보다 먼저 실행된다")
	void rtmsApplicationRunnerRunsBeforeCoordinateReadiness() {
		RtmsOneShotTradeIngestRunner runner = mock(RtmsOneShotTradeIngestRunner.class);
		RtmsOneShotIngestApplicationRunner applicationRunner = new RtmsOneShotIngestApplicationRunner(
			runner,
			new RtmsOneShotIngestProperties(false, null, null, null, false),
			rtmsProperties("DUMMY")
		);

		assertThat(applicationRunner.getOrder()).isEqualTo(ApplicationRunnerOrders.RTMS_ONE_SHOT_INGEST);
		assertThat(applicationRunner.getOrder()).isLessThan(ApplicationRunnerOrders.COORDINATE_READINESS);
	}

	@Test
	@DisplayName("enabled local trigger는 request property가 없으면 live ingest 전에 실패한다")
	void enabledLocalTriggerRequiresExplicitRequestProperties() {
		RtmsOneShotTradeIngestRunner runner = mock(RtmsOneShotTradeIngestRunner.class);
		RtmsOneShotIngestApplicationRunner applicationRunner = new RtmsOneShotIngestApplicationRunner(
			runner,
			new RtmsOneShotIngestProperties(true, " ", "202512", 1, false),
			rtmsProperties("DUMMY")
		);

		assertThatThrownBy(() -> applicationRunner.run(new DefaultApplicationArguments()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("lawdCd is required");
		verifyNoInteractions(runner);
	}

	@Test
	@DisplayName("enabled local trigger는 live ingest 전에 service key를 검증한다")
	void enabledLocalTriggerRequiresServiceKeyBeforeLiveIngest() {
		RtmsOneShotTradeIngestRunner runner = mock(RtmsOneShotTradeIngestRunner.class);
		RtmsOneShotIngestApplicationRunner applicationRunner = new RtmsOneShotIngestApplicationRunner(
			runner,
			new RtmsOneShotIngestProperties(true, "11680", "202512", 1, false),
			rtmsProperties(" ")
		);

		assertThatThrownBy(() -> applicationRunner.run(new DefaultApplicationArguments()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("APT_SERVICE_KEY");
		verifyNoInteractions(runner);
	}

	@Test
	@DisplayName("enabled RTMS ingest는 live fetch 전에 coordinate source preflight를 통과해야 한다")
	void enabledRtmsIngestRequiresCoordinateSourcePreflightBeforeLiveFetch() {
		RtmsOneShotTradeIngestRunner runner = mock(RtmsOneShotTradeIngestRunner.class);
		RtmsOneShotIngestApplicationRunner applicationRunner = new RtmsOneShotIngestApplicationRunner(
			runner,
			new RtmsOneShotIngestProperties(true, "11680", "202512", 1, false),
			rtmsProperties("DUMMY"),
			() -> {
				throw new IllegalStateException("COORDINATE_SOURCE_DB_JDBC_URL is required for RTMS ingest");
			}
		);

		assertThatThrownBy(() -> applicationRunner.run(new DefaultApplicationArguments()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("COORDINATE_SOURCE_DB_JDBC_URL");
		verifyNoInteractions(runner);
	}

	@Test
	@DisplayName("preflight-only mode는 live fetch나 DB ingest 없이 configuration을 검증한다")
	void preflightOnlyModeValidatesConfigurationWithoutLiveFetchOrDbIngest(CapturedOutput output) throws Exception {
		RtmsOneShotTradeIngestRunner runner = mock(RtmsOneShotTradeIngestRunner.class);
		RtmsOneShotIngestApplicationRunner applicationRunner = new RtmsOneShotIngestApplicationRunner(
			runner,
			new RtmsOneShotIngestProperties(true, " 11680 ", " 202512 ", 2, true),
			rtmsProperties("DUMMY")
		);

		applicationRunner.run(new DefaultApplicationArguments());

		verifyNoInteractions(runner);
		assertThat(output).contains("RTMS one-shot ingest preflight completed")
			.contains("baseUrl=https://example.invalid")
			.contains("path=/rtms")
			.contains("lawdCd=11680")
			.contains("dealYmd=202512")
			.contains("pageNo=2")
			.contains("numOfRows=100")
			.doesNotContain("APT_SERVICE_KEY")
			.doesNotContain("serviceKey")
			.doesNotContain("payload");
	}

	@Test
	@DisplayName("enabled local trigger는 live ingest 전에 잘못된 lawdCd를 거부한다")
	void enabledLocalTriggerRejectsInvalidLawdCdBeforeLiveIngest() {
		RtmsOneShotTradeIngestRunner runner = mock(RtmsOneShotTradeIngestRunner.class);
		RtmsOneShotIngestApplicationRunner applicationRunner = new RtmsOneShotIngestApplicationRunner(
			runner,
			new RtmsOneShotIngestProperties(true, "1168A", "202512", 1, false),
			rtmsProperties("DUMMY")
		);

		assertThatThrownBy(() -> applicationRunner.run(new DefaultApplicationArguments()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("lawdCd");
		verifyNoInteractions(runner);
	}

	@Test
	@DisplayName("enabled local trigger는 live ingest 전에 잘못된 dealYmd를 거부한다")
	void enabledLocalTriggerRejectsInvalidDealYmdBeforeLiveIngest() {
		RtmsOneShotTradeIngestRunner runner = mock(RtmsOneShotTradeIngestRunner.class);
		RtmsOneShotIngestApplicationRunner applicationRunner = new RtmsOneShotIngestApplicationRunner(
			runner,
			new RtmsOneShotIngestProperties(true, "11680", "202513", 1, false),
			rtmsProperties("DUMMY")
		);

		assertThatThrownBy(() -> applicationRunner.run(new DefaultApplicationArguments()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("dealYmd");
		verifyNoInteractions(runner);
	}

	@Test
	@DisplayName("enabled local trigger는 explicit RTMS request를 실행하고 count summary만 log한다")
	void enabledLocalTriggerRunsExplicitRequestAndLogsSummary(CapturedOutput output) throws Exception {
		RtmsOneShotTradeIngestRunner runner = mock(RtmsOneShotTradeIngestRunner.class);
		RtmsOneShotIngestApplicationRunner applicationRunner = new RtmsOneShotIngestApplicationRunner(
			runner,
			new RtmsOneShotIngestProperties(true, " 11680 ", " 202512 ", 2, false),
			rtmsProperties("DUMMY")
		);
		RtmsApartmentTradeRequest expectedRequest = new RtmsApartmentTradeRequest("11680", "202512", 2);
		when(runner.ingest(expectedRequest)).thenReturn(new IngestResult(3, 3, 1, 1, 1, 0));

		applicationRunner.run(new DefaultApplicationArguments());

		verify(runner).ingest(expectedRequest);
		assertThat(output).contains("RTMS one-shot ingest completed")
			.contains("lawdCd=11680")
			.contains("dealYmd=202512")
			.contains("pageNo=2")
			.contains("read=3")
			.contains("rawSaved=3")
			.contains("normalizedInserted=1")
			.contains("duplicateSkipped=1")
			.contains("matchFailed=1")
			.contains("parseFailed=0")
			.doesNotContain("APT_SERVICE_KEY")
			.doesNotContain("serviceKey")
			.doesNotContain("payload");
	}

	@Test
	@DisplayName("monthly-refresh preflight는 live fetch나 DB ingest 없이 월별 plan만 log한다")
	void monthlyRefreshPreflightLogsPlanWithoutLiveFetchOrDbIngest(CapturedOutput output) throws Exception {
		RtmsOneShotTradeIngestRunner runner = mock(RtmsOneShotTradeIngestRunner.class);
		RtmsMonthlyRefreshRunner monthlyRefreshRunner = mock(RtmsMonthlyRefreshRunner.class);
		RtmsOneShotIngestApplicationRunner applicationRunner = new RtmsOneShotIngestApplicationRunner(
			runner,
			monthlyRefreshRunner,
			new RtmsOneShotIngestProperties(true, " 11680 ", " 202501 ", 1, true, "monthly-refresh", 1),
			rtmsProperties("DUMMY")
		);

		applicationRunner.run(new DefaultApplicationArguments());

		verifyNoInteractions(runner, monthlyRefreshRunner);
		assertThat(output).contains("RTMS monthly refresh preflight completed")
			.contains("baseUrl=https://example.invalid")
			.contains("path=/rtms")
			.contains("lawdCd=11680")
			.contains("dealYmds=[202501, 202412]")
			.contains("lookbackMonths=1")
			.contains("numOfRows=100")
			.doesNotContain("APT_SERVICE_KEY")
			.doesNotContain("serviceKey")
			.doesNotContain("payload");
	}

	@Test
	@DisplayName("monthly-refresh mode는 plan을 실행하고 aggregate summary만 log한다")
	void monthlyRefreshModeRunsPlanAndLogsAggregateSummary(CapturedOutput output) throws Exception {
		RtmsOneShotTradeIngestRunner runner = mock(RtmsOneShotTradeIngestRunner.class);
		RtmsMonthlyRefreshRunner monthlyRefreshRunner = mock(RtmsMonthlyRefreshRunner.class);
		RtmsOneShotIngestApplicationRunner applicationRunner = new RtmsOneShotIngestApplicationRunner(
			runner,
			monthlyRefreshRunner,
			new RtmsOneShotIngestProperties(true, " 11680 ", " 202501 ", 1, false, "monthly-refresh", 1),
			rtmsProperties("DUMMY")
		);
		RtmsMonthlyRefreshPlan expectedPlan = new RtmsMonthlyRefreshPlan("11680", "202501", 1);
		when(monthlyRefreshRunner.refresh(expectedPlan)).thenReturn(new RtmsMonthlyRefreshReport(List.of(
			RtmsMonthlyRefreshRunSummary.completed("11680", "202501", 1, new IngestResult(2, 2, 1, 1, 0, 0)),
			RtmsMonthlyRefreshRunSummary.completed("11680", "202412", 2, new IngestResult(3, 3, 0, 2, 1, 0))
		)));

		applicationRunner.run(new DefaultApplicationArguments());

		verifyNoInteractions(runner);
		verify(monthlyRefreshRunner).refresh(expectedPlan);
		assertThat(output).contains("RTMS monthly refresh completed")
			.contains("lawdCd=11680")
			.contains("dealYmds=[202501, 202412]")
			.contains("monthCount=2")
			.contains("pageCount=3")
			.contains("read=5")
			.contains("rawSaved=5")
			.contains("normalizedInserted=1")
			.contains("duplicateSkipped=3")
			.contains("matchFailed=1")
			.contains("parseFailed=0")
			.contains("hasNewData=true")
			.doesNotContain("APT_SERVICE_KEY")
			.doesNotContain("serviceKey")
			.doesNotContain("payload");
	}

	@Test
	@DisplayName("nationwide-backfill preflight는 live fetch나 DB write 없이 chunk 계획만 log한다")
	void nationwideBackfillPreflightLogsChunkPlanWithoutLiveFetchOrDbWrite(CapturedOutput output) throws Exception {
		RtmsOneShotTradeIngestRunner runner = mock(RtmsOneShotTradeIngestRunner.class);
		RtmsMonthlyRefreshRunner monthlyRefreshRunner = mock(RtmsMonthlyRefreshRunner.class);
		RtmsNationwideBackfillRunner nationwideBackfillRunner = mock(RtmsNationwideBackfillRunner.class);
		RtmsOneShotIngestApplicationRunner applicationRunner = new RtmsOneShotIngestApplicationRunner(
			runner,
			monthlyRefreshRunner,
			nationwideBackfillRunner,
			nationwideProperties(true),
			rtmsProperties("DUMMY"),
			RtmsCoordinateSourcePreflight.noop()
		);

		applicationRunner.run(new DefaultApplicationArguments());

		verifyNoInteractions(runner, monthlyRefreshRunner, nationwideBackfillRunner);
		assertThat(output).contains("RTMS nationwide backfill preflight completed")
			.contains("jobKey=rtms-national-test")
			.contains("dealYmdFrom=201201")
			.contains("dealYmdTo=201202")
			.contains("lawdCount=2")
			.contains("chunkCount=4")
			.contains("workerId=worker-1")
			.contains("chunkLimit=5")
			.doesNotContain("APT_SERVICE_KEY")
			.doesNotContain("serviceKey")
			.doesNotContain("payload");
	}

	@Test
	@DisplayName("nationwide-backfill mode는 backfill runner를 실행하고 실패 chunk summary만 log한다")
	void nationwideBackfillModeRunsBackfillRunnerAndLogsSummary(CapturedOutput output) throws Exception {
		RtmsOneShotTradeIngestRunner runner = mock(RtmsOneShotTradeIngestRunner.class);
		RtmsMonthlyRefreshRunner monthlyRefreshRunner = mock(RtmsMonthlyRefreshRunner.class);
		RtmsNationwideBackfillRunner nationwideBackfillRunner = mock(RtmsNationwideBackfillRunner.class);
		RtmsOneShotIngestApplicationRunner applicationRunner = new RtmsOneShotIngestApplicationRunner(
			runner,
			monthlyRefreshRunner,
			nationwideBackfillRunner,
			nationwideProperties(false),
			rtmsProperties("DUMMY"),
			RtmsCoordinateSourcePreflight.noop()
		);
		RtmsNationwideBackfillPlan expectedPlan = new RtmsNationwideBackfillPlan(
			"rtms-national-test",
			List.of("11110", "11680"),
			"201201",
			"201202"
		);
		when(nationwideBackfillRunner.run(expectedPlan)).thenReturn(new RtmsNationwideBackfillReport(
			1L,
			com.home.domain.ingest.backfill.RtmsBackfillJobStatus.PARTIAL,
			new com.home.application.ingest.backfill.RtmsBackfillChunkStatusCounts(0, 0, 2, 1, 1, 1, 0),
			1
		));

		applicationRunner.run(new DefaultApplicationArguments());

		verifyNoInteractions(runner, monthlyRefreshRunner);
		verify(nationwideBackfillRunner).run(expectedPlan);
		assertThat(output).contains("RTMS nationwide backfill completed")
			.contains("jobId=1")
			.contains("jobStatus=PARTIAL")
			.contains("completedChunks=2")
			.contains("failedChunks=1")
			.contains("partialChunks=1")
			.contains("blockedChunks=1")
			.contains("recoveredStaleChunks=1")
			.doesNotContain("APT_SERVICE_KEY")
			.doesNotContain("serviceKey")
			.doesNotContain("payload");
	}

	@Test
	@DisplayName("nationwide-backfill mode는 backfill runner bean이 없으면 live fetch 전에 실패한다")
	void nationwideBackfillModeRequiresBackfillRunnerBeforeLiveFetch() {
		RtmsOneShotTradeIngestRunner runner = mock(RtmsOneShotTradeIngestRunner.class);
		RtmsMonthlyRefreshRunner monthlyRefreshRunner = mock(RtmsMonthlyRefreshRunner.class);
		RtmsOneShotIngestApplicationRunner applicationRunner = new RtmsOneShotIngestApplicationRunner(
			runner,
			monthlyRefreshRunner,
			null,
			nationwideProperties(false),
			rtmsProperties("DUMMY"),
			RtmsCoordinateSourcePreflight.noop()
		);

		assertThatThrownBy(() -> applicationRunner.run(new DefaultApplicationArguments()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("RtmsNationwideBackfillRunner");
		verifyNoInteractions(runner, monthlyRefreshRunner);
	}

	@Test
	@DisplayName("nationwide-backfill properties는 lawd 목록, 기간, lease, retry, chunk limit을 실행 plan으로 변환한다")
	void nationwideBackfillPropertiesBuildPlanAndOptions() {
		RtmsOneShotIngestProperties properties = nationwideProperties(false);

		assertThat(properties.ingestMode()).isEqualTo(RtmsIngestMode.NATIONWIDE_BACKFILL);
		assertThat(properties.nationwideBackfillPlan()).satisfies(plan -> {
			assertThat(plan.jobKey()).isEqualTo("rtms-national-test");
			assertThat(plan.dealYmds()).containsExactly("201201", "201202");
			assertThat(plan.chunks()).hasSize(4);
		});
		assertThat(properties.nationwideBackfillOptions()).satisfies(options -> {
			assertThat(options.workerId()).isEqualTo("worker-1");
			assertThat(options.leaseDuration()).isEqualTo(java.time.Duration.ofMinutes(30));
			assertThat(options.maxAttemptCount()).isEqualTo(3);
			assertThat(options.chunkLimit()).isEqualTo(5);
		});
	}

	@Test
	@DisplayName("nationwide-backfill properties는 lawd 목록과 option validation을 live fetch 전에 거부한다")
	void nationwideBackfillPropertiesRejectMissingLawdAndInvalidOptions() {
		RtmsOneShotIngestProperties missingLawdCds = new RtmsOneShotIngestProperties(
			true,
			"11680",
			"202606",
			1,
			false,
			"nationwide-backfill",
			0,
			false,
			" ",
			"201201",
			"201202",
			"rtms-national-test",
			"worker-1",
			30,
			3,
			5
		);

		assertThatThrownBy(missingLawdCds::nationwideBackfillPlan)
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("nationwide.lawd-cds");
		assertThatThrownBy(() -> new RtmsNationwideBackfillOptions(" ", java.time.Duration.ofMinutes(30), 3, 5))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("workerId");
		assertThatThrownBy(() -> new RtmsNationwideBackfillOptions("worker-1", java.time.Duration.ofMinutes(-1), 3, 5))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("leaseDuration");
		assertThatThrownBy(() -> new RtmsNationwideBackfillOptions("worker-1", java.time.Duration.ofMinutes(30), 0, 5))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("maxAttemptCount");
		assertThatThrownBy(() -> new RtmsNationwideBackfillOptions("worker-1", java.time.Duration.ofMinutes(30), 3, 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("chunkLimit");
	}

	private RtmsApartmentTradeProperties rtmsProperties(String serviceKey) {
		return new RtmsApartmentTradeProperties("https://example.invalid", "/rtms", serviceKey, 100, 1_000, 1_000);
	}

	private RtmsOneShotIngestProperties nationwideProperties(boolean preflightOnly) {
		return new RtmsOneShotIngestProperties(
			true,
			"11680",
			"202606",
			1,
			preflightOnly,
			"nationwide-backfill",
			0,
			false,
			"11110,11680",
			"201201",
			"201202",
			"rtms-national-test",
			"worker-1",
			30,
			3,
			5
		);
	}
}
