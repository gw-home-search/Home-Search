package com.home.application.ingest.reconciliation;

public record RawIngestReconciliationResult(
	int processed,
	int normalized
) {

	public static RawIngestReconciliationResult empty() {
		return new RawIngestReconciliationResult(0, 0);
	}

	public RawIngestReconciliationResult plusNormalized() {
		return new RawIngestReconciliationResult(processed + 1, normalized + 1);
	}
}
