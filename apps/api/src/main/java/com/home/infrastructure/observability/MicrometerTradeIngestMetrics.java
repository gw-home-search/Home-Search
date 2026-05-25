package com.home.infrastructure.observability;

import java.util.Objects;

import com.home.application.ingest.IngestResult;
import com.home.application.ingest.TradeIngestMetrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

public class MicrometerTradeIngestMetrics implements TradeIngestMetrics {

	static final String METRIC_NAME = "home.search.ingest.items";

	private final MeterRegistry meterRegistry;

	public MicrometerTradeIngestMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = Objects.requireNonNull(meterRegistry);
	}

	@Override
	public void record(String source, IngestResult result) {
		Objects.requireNonNull(result);
		String sourceTag = source == null || source.isBlank() ? "unknown" : source.strip();
		increment(counter(sourceTag, "read"), result.read());
		increment(counter(sourceTag, "raw_saved"), result.rawSaved());
		increment(counter(sourceTag, "normalized_inserted"), result.normalizedInserted());
		increment(counter(sourceTag, "duplicate_skipped"), result.duplicateSkipped());
		increment(counter(sourceTag, "match_failed"), result.matchFailed());
		increment(counter(sourceTag, "parse_failed"), result.parseFailed());
	}

	private Counter counter(String source, String result) {
		return Counter.builder(METRIC_NAME)
			.description("RTMS ingest item counts")
			.tag("source", source)
			.tag("result", result)
			.register(meterRegistry);
	}

	private void increment(Counter counter, long amount) {
		if (amount > 0) {
			counter.increment(amount);
		}
	}
}
