package com.home.application.ingest.metadata;

public interface OdcComplexMetadataResolver {
	ComplexMetadataResolution resolveOdc(ComplexMetadataLookup lookup);

	boolean isOdcConfigured();
}
