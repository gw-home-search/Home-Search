package com.home.application.ingest;

public record RawIngestReconciliationCandidate(
	Long rawIngestId,
	Long tradeId
) {
}
