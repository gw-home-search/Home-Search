package com.home.infrastructure.external.rtms;

import java.util.Objects;
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
		this(client, () -> Objects.requireNonNull(ingestService));
	}

	RtmsOneShotTradeIngestRunner(
		RtmsApartmentTradeClient client,
		Supplier<OpenApiTradeIngestService> ingestServiceSupplier
	) {
		this.client = Objects.requireNonNull(client);
		this.ingestServiceSupplier = Objects.requireNonNull(ingestServiceSupplier);
	}

	public IngestResult ingest(RtmsApartmentTradeRequest request) {
		OpenApiTradeIngestService ingestService = Objects.requireNonNull(
			ingestServiceSupplier.get(),
			"OpenApiTradeIngestService is required"
		);
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
