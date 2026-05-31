package com.home.application.ingest;

public record ComplexMetadataLookup(
	Long complexId,
	String aptSeq,
	String aptName,
	String pnu,
	String parcelAddress
) {
}
