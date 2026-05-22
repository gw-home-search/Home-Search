package com.home.infrastructure.external.rtms;

import com.home.application.ingest.IngestResult;
import com.home.application.ingest.OpenApiTradeIngestBatch;
import com.home.application.ingest.OpenApiTradeIngestService;

public class RtmsOneShotTradeIngestRunner {

	private final RtmsApartmentTradeClient client;
	private final OpenApiTradeIngestService ingestService;

	public RtmsOneShotTradeIngestRunner(
		RtmsApartmentTradeClient client,
		OpenApiTradeIngestService ingestService
	) {
		this.client = client;
		this.ingestService = ingestService;
	}

	public IngestResult ingest(RtmsApartmentTradeRequest request) {
		OpenApiTradeIngestBatch batch = client.fetch(request);
		return ingestService.ingest(batch);
	}
}
