package com.home.infrastructure.external.rtms;

import java.util.function.Supplier;

import com.home.application.ingest.trade.IngestResult;
import com.home.application.ingest.trade.OpenApiTradeIngestBatch;
import com.home.application.ingest.trade.OpenApiTradeIngestService;

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
		RtmsApartmentTradeRequest currentRequest = request;
		IngestResult total = IngestResult.empty();
		while (true) {
			RtmsApartmentTradePage page = client.fetchPage(currentRequest);
			OpenApiTradeIngestBatch batch = page.batch();
			total = total.plus(ingestService.ingest(batch));
			if (!page.hasNextPage()) {
				return total;
			}
			currentRequest = page.nextRequest();
		}
	}
}
