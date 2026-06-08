package com.home.application.ingest.metadata;

@FunctionalInterface
public interface ComplexMetadataEnrichmentClient {

	ComplexMetadataResolution resolve(ComplexMetadataLookup lookup);

	default boolean isConfigured() {
		return true;
	}

	static ComplexMetadataEnrichmentClient noop() {
		return new ComplexMetadataEnrichmentClient() {
			@Override
			public ComplexMetadataResolution resolve(ComplexMetadataLookup lookup) {
				return ComplexMetadataResolution.unavailable(null, "complex metadata enrichment client unavailable");
			}

			@Override
			public boolean isConfigured() {
				return false;
			}
		};
	}
}
