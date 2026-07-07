package com.home.application.ingest.trade;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TradeIngestItemOutcomeTest {

	@Test
	@DisplayName("item outcome은 ingest result count로 누적된다")
	void itemOutcomesAccumulateIntoIngestResult() {
		TradeIngestItemOutcome.Accumulator accumulator = TradeIngestItemOutcome.accumulator();

		accumulator.add(TradeIngestItemOutcome.normalized());
		accumulator.add(TradeIngestItemOutcome.duplicate());
		accumulator.add(TradeIngestItemOutcome.canceled());
		accumulator.add(TradeIngestItemOutcome.matchFailed());
		accumulator.add(TradeIngestItemOutcome.parseFailed());

		assertThat(accumulator.toResult(5, 5)).isEqualTo(new IngestResult(5, 5, 1, 1, 1, 1, 1));
	}
}
