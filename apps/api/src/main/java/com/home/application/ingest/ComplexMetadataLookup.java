package com.home.application.ingest;

public record ComplexMetadataLookup(
	Long complexId,
	String aptSeq,
	String aptName,
	String pnu,
	String parcelAddress,
	int attempts
) {

	public ComplexMetadataLookup(Long complexId, String aptSeq, String aptName, String pnu, String parcelAddress) {
		this(complexId, aptSeq, aptName, pnu, parcelAddress, 0);
	}
}
