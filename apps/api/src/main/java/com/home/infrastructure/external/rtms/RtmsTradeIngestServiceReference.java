package com.home.infrastructure.external.rtms;

import java.util.Objects;
import java.util.function.Supplier;

import com.home.application.ingest.trade.OpenApiTradeIngestService;

final class RtmsTradeIngestServiceReference {

	private final Supplier<OpenApiTradeIngestService> supplier;

	private RtmsTradeIngestServiceReference(Supplier<OpenApiTradeIngestService> supplier) {
		this.supplier = Objects.requireNonNull(supplier);
	}

	static RtmsTradeIngestServiceReference of(OpenApiTradeIngestService ingestService) {
		Objects.requireNonNull(ingestService);
		return new RtmsTradeIngestServiceReference(() -> ingestService);
	}

	static RtmsTradeIngestServiceReference lazy(Supplier<OpenApiTradeIngestService> supplier) {
		return new RtmsTradeIngestServiceReference(supplier);
	}

	OpenApiTradeIngestService get() {
		return Objects.requireNonNull(supplier.get(), "OpenApiTradeIngestService is required");
	}
}
