package com.home.infrastructure.external.rtms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import com.home.application.ingest.IngestResult;
import com.home.application.ingest.OpenApiTradeIngestBatch;
import com.home.application.ingest.OpenApiTradeIngestService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

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
		when(client.fetch(request)).thenReturn(batch);
		when(ingestService.ingest(batch)).thenReturn(expected);

		IngestResult result = runner.ingest(request);

		assertThat(result).isEqualTo(expected);
		verify(client).fetch(request);
		verify(ingestService).ingest(batch);
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

	private RtmsApartmentTradeProperties rtmsProperties(String serviceKey) {
		return new RtmsApartmentTradeProperties("https://example.invalid", "/rtms", serviceKey, 100, 1_000, 1_000);
	}
}
