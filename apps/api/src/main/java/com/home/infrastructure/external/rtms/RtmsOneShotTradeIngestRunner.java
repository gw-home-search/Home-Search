package com.home.infrastructure.external.rtms;

import com.home.application.ingest.trade.IngestResult;
import com.home.application.ingest.trade.OpenApiTradeIngestBatch;
import com.home.application.ingest.trade.OpenApiTradeIngestService;

public class RtmsOneShotTradeIngestRunner {

	private final RtmsApartmentTradeClient client;
	private final RtmsTradeIngestServiceReference ingestServiceReference;

	public RtmsOneShotTradeIngestRunner(
		RtmsApartmentTradeClient client,
		OpenApiTradeIngestService ingestService
	) {
		this(client, RtmsTradeIngestServiceReference.of(ingestService));
	}

	RtmsOneShotTradeIngestRunner(
		RtmsApartmentTradeClient client,
		RtmsTradeIngestServiceReference ingestServiceReference
	) {
		this.client = client;
		this.ingestServiceReference = ingestServiceReference;
	}

	public IngestResult ingest(RtmsApartmentTradeRequest request) {
		OpenApiTradeIngestService ingestService = ingestServiceReference.get();
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
