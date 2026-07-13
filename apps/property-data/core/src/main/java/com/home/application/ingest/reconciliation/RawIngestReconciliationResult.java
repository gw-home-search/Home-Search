package com.home.application.ingest.reconciliation;

import com.home.application.ingest.trade.TradeIngestItemOutcome;

public record RawIngestReconciliationResult(
	int processed,
	int normalized
) {

	public static RawIngestReconciliationResult empty() {
		return new RawIngestReconciliationResult(0, 0);
	}

	public RawIngestReconciliationResult plus(TradeIngestItemOutcome outcome) {
		return new RawIngestReconciliationResult(
			processed + 1,
			normalized + Math.toIntExact(outcome.normalizedInsertedCount())
		);
	}
}
