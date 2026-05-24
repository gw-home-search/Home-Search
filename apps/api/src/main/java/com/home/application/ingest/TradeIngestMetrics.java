package com.home.application.ingest;

@FunctionalInterface
public interface TradeIngestMetrics {

	void record(String source, IngestResult result);

	default void record(IngestResult result) {
		record("unknown", result);
	}

	static TradeIngestMetrics noop() {
		return (source, result) -> {
		};
	}
}
