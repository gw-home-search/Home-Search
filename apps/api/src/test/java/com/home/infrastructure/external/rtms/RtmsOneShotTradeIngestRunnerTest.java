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
	@DisplayName("one-shot runner fetches RTMS batch and delegates to OpenApiTradeIngestService")
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
	@DisplayName("one-shot runner fails before live fetch when ingest service is unavailable")
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
	@DisplayName("disabled local trigger does not call the RTMS one-shot runner")
	void disabledLocalTriggerDoesNotCallRunner() throws Exception {
		RtmsOneShotTradeIngestRunner runner = mock(RtmsOneShotTradeIngestRunner.class);
		RtmsOneShotIngestApplicationRunner applicationRunner = new RtmsOneShotIngestApplicationRunner(
			runner,
			new RtmsOneShotIngestProperties(false, null, null, null)
		);

		applicationRunner.run(new DefaultApplicationArguments());

		verifyNoInteractions(runner);
	}

	@Test
	@DisplayName("enabled local trigger fails before live ingest when request properties are missing")
	void enabledLocalTriggerRequiresExplicitRequestProperties() {
		RtmsOneShotTradeIngestRunner runner = mock(RtmsOneShotTradeIngestRunner.class);
		RtmsOneShotIngestApplicationRunner applicationRunner = new RtmsOneShotIngestApplicationRunner(
			runner,
			new RtmsOneShotIngestProperties(true, " ", "202512", 1)
		);

		assertThatThrownBy(() -> applicationRunner.run(new DefaultApplicationArguments()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("lawdCd is required");
		verifyNoInteractions(runner);
	}

	@Test
	@DisplayName("enabled local trigger runs explicit RTMS request and logs only count summary")
	void enabledLocalTriggerRunsExplicitRequestAndLogsSummary(CapturedOutput output) throws Exception {
		RtmsOneShotTradeIngestRunner runner = mock(RtmsOneShotTradeIngestRunner.class);
		RtmsOneShotIngestApplicationRunner applicationRunner = new RtmsOneShotIngestApplicationRunner(
			runner,
			new RtmsOneShotIngestProperties(true, " 11680 ", " 202512 ", 2)
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
}
