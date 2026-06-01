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
			resolved + (status == ComplexMetadataStatus.RESOLVED ? 1 : 0),
			partial + (status == ComplexMetadataStatus.PARTIAL ? 1 : 0),
			ambiguous + (status == ComplexMetadataStatus.AMBIGUOUS ? 1 : 0),
			unavailable + (status == ComplexMetadataStatus.UNAVAILABLE ? 1 : 0),
			failed + (status == ComplexMetadataStatus.FAILED ? 1 : 0)
		);
	}
}
