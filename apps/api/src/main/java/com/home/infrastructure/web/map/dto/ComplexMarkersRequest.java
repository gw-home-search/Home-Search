package com.home.infrastructure.web.map.dto;

import jakarta.validation.constraints.NotNull;

public record ComplexMarkersRequest(
	@NotNull Double swLat,
	@NotNull Double swLng,
	@NotNull Double neLat,
	@NotNull Double neLng,
	Integer pyeongMin,
	Integer pyeongMax,
	Double priceEokMin,
	Double priceEokMax,
	Integer ageMin,
	Integer ageMax,
	Long unitMin,
	Long unitMax
) {
}
