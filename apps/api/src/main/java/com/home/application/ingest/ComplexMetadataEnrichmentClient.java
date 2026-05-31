package com.home.application.ingest;

@FunctionalInterface
public interface ComplexMetadataEnrichmentClient {

	ComplexMetadataResolution resolve(ComplexMetadataLookup lookup);

	static ComplexMetadataEnrichmentClient noop() {
		return lookup -> ComplexMetadataResolution.unavailable(null, "complex metadata enrichment client unavailable");
	}
}
