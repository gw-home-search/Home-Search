package com.home.infrastructure.external.rtms;

import java.util.function.Supplier;

import com.home.application.ingest.IngestResult;
import com.home.application.ingest.OpenApiTradeIngestBatch;
import com.home.application.ingest.OpenApiTradeIngestService;

public class RtmsOneShotTradeIngestRunner {

	private final RtmsApartmentTradeClient client;
	private final Supplier<OpenApiTradeIngestService> ingestServiceSupplier;

	public RtmsOneShotTradeIngestRunner(
		RtmsApartmentTradeClient client,
		OpenApiTradeIngestService ingestService
	) {
		this.client = client;
		this.ingestServiceSupplier = () -> ingestService;
	}

	RtmsOneShotTradeIngestRunner(
		RtmsApartmentTradeClient client,
		Supplier<OpenApiTradeIngestService> ingestServiceSupplier
	) {
		this.client = client;
		this.ingestServiceSupplier = ingestServiceSupplier;
	}

	public IngestResult ingest(RtmsApartmentTradeRequest request) {
		OpenApiTradeIngestService ingestService = ingestServiceSupplier.get();
		OpenApiTradeIngestBatch batch = client.fetch(request);
		return ingestService.ingest(batch);
	}
}
