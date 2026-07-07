package com.home.application.ingest.metadata;

import com.home.domain.complex.metadata.ComplexMetadataLookupPath;

public record ComplexMetadataLookupEvidence(
	ComplexMetadataLookupPath lookupPath,
	String requestedPnu,
	String resolvedSourcePnu,
	Long aliasId,
	Integer candidateCount
) {

	public static ComplexMetadataLookupEvidence none() {
		return new ComplexMetadataLookupEvidence(ComplexMetadataLookupPath.NONE, null, null, null, null);
	}
}
