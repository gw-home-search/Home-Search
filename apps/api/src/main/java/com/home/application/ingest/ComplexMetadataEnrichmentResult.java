package com.home.application.ingest;

public record ComplexMetadataEnrichmentResult(
	int processed,
	int resolved,
	int partial,
	int ambiguous,
	int unavailable,
	int failed
) {

	public static ComplexMetadataEnrichmentResult empty() {
		return new ComplexMetadataEnrichmentResult(0, 0, 0, 0, 0, 0);
	}

	ComplexMetadataEnrichmentResult plus(ComplexMetadataStatus status) {
		return new ComplexMetadataEnrichmentResult(
			processed + 1,
			resolved + (status.isResolved() ? 1 : 0),
			partial + (status.isPartial() ? 1 : 0),
			ambiguous + (status.isAmbiguous() ? 1 : 0),
			unavailable + (status.isUnavailable() ? 1 : 0),
			failed + (status.isFailed() ? 1 : 0)
		);
	}
}
