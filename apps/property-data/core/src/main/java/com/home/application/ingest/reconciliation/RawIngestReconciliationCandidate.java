package com.home.application.ingest.reconciliation;

public record RawIngestReconciliationCandidate(
	Long rawIngestId,
	Long tradeId
) {
}
