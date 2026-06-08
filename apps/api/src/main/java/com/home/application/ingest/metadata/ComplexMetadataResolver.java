package com.home.application.ingest.metadata;

@FunctionalInterface
public interface ComplexMetadataResolver {

	ComplexMetadataResolution resolve(String pnu, String parcelAddress);

	static ComplexMetadataResolver noop() {
		return (pnu, parcelAddress) -> ComplexMetadataResolution.unavailable(
			null,
			"complex metadata resolver unavailable"
		);
	}
}
