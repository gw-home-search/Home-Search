package com.home.infrastructure.web.map.dto;

public record ComplexMarkerResponse(
	Long parcelId,
	Long complexId,
	Double lat,
	Double lng,
	Long latestDealAmount,
	Long unitCntSum
) {

	public ComplexMarkerResponse(
		Long parcelId,
		Double lat,
		Double lng,
		Long latestDealAmount,
		Long unitCntSum
	) {
		this(parcelId, null, lat, lng, latestDealAmount, unitCntSum);
	}
}
