package com.home.application.map;

public record ComplexMarkerResult(
	Long parcelId,
	Long complexId,
	String name,
	Double lat,
	Double lng,
	Long latestDealAmount,
	Long unitCntSum
) {

	public ComplexMarkerResult(
		Long parcelId,
		Double lat,
		Double lng,
		Long latestDealAmount,
		Long unitCntSum
	) {
		this(parcelId, null, null, lat, lng, latestDealAmount, unitCntSum);
	}

	public ComplexMarkerResult(
		Long parcelId,
		Long complexId,
		Double lat,
		Double lng,
		Long latestDealAmount,
		Long unitCntSum
	) {
		this(parcelId, complexId, null, lat, lng, latestDealAmount, unitCntSum);
	}
}
