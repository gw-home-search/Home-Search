package com.home.infrastructure.external.rtms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.home.application.ingest.IngestResult;
import com.home.application.ingest.OpenApiTradeIngestBatch;
import com.home.application.ingest.OpenApiTradeIngestService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
