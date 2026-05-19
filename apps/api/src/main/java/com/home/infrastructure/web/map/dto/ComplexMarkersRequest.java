package com.home.infrastructure.web.map.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record ComplexMarkersRequest(
	@DecimalMin("-90.0")
	@DecimalMax("90.0")
	@NotNull Double swLat,
	@DecimalMin("-180.0")
	@DecimalMax("180.0")
	@NotNull Double swLng,
	@DecimalMin("-90.0")
	@DecimalMax("90.0")
	@NotNull Double neLat,
	@DecimalMin("-180.0")
	@DecimalMax("180.0")
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

	@AssertTrue
	public boolean isLatitudeBoundsOrdered() {
		return swLat == null || neLat == null || swLat <= neLat;
	}

	@AssertTrue
	public boolean isLongitudeBoundsOrdered() {
		return swLng == null || neLng == null || swLng <= neLng;
	}
}
