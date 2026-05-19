package com.home.infrastructure.web.map.dto;

public record ComplexMarkerResponse(
	Long parcelId,
	Double lat,
	Double lng,
	Long latestDealAmount,
	Long unitCntSum
) {
}
