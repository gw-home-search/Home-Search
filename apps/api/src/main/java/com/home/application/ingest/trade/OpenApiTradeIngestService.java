package com.home.application.ingest.trade;

import java.util.Objects;

/**
 * Open API trade item을 raw evidence로 먼저 저장한 뒤 complex match와 normalized trade insert를 수행하는 ingest service입니다.
 */
public class OpenApiTradeIngestService {

	private final TradeIngestItemProcessor itemProcessor;
	private final TradeIngestMetrics tradeIngestMetrics;

	public OpenApiTradeIngestService(
		TradeIngestItemProcessor itemProcessor,
		TradeIngestMetrics tradeIngestMetrics
	) {
		this.itemProcessor = Objects.requireNonNull(itemProcessor);
		this.tradeIngestMetrics = Objects.requireNonNull(tradeIngestMetrics);
	}

	/**
	 * batch의 각 item을 raw row로 보존하고, parse/match/dedupe 결과에 따라 raw status와 normalized trade를 갱신합니다.
	 *
	 * @param batch live Open API 호출 없이 준비된 수집 batch
	 * @return raw 저장, normalized insert, duplicate, 실패 count
	 */
	public IngestResult ingest(OpenApiTradeIngestBatch batch) {
		TradeIngestItemOutcome.Accumulator accumulator = TradeIngestItemOutcome.accumulator();

		for (OpenApiTradeItem item : batch.items()) {
			accumulator.add(itemProcessor.process(batch, item));
		}

		IngestResult result = accumulator.toResult(batch.items().size(), batch.items().size());
		tradeIngestMetrics.record(batch.source(), result);
		return result;
	}
}
