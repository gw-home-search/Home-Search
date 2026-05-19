package com.home.infrastructure.web.map.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

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
	@PositiveOrZero
	Integer pyeongMin,
	@PositiveOrZero
	Integer pyeongMax,
	@DecimalMin("0.0")
	Double priceEokMin,
	@DecimalMin("0.0")
	Double priceEokMax,
	@PositiveOrZero
	Integer ageMin,
	@PositiveOrZero
	Integer ageMax,
	@PositiveOrZero
	Long unitMin,
	@PositiveOrZero
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

	@AssertTrue
	public boolean isPyeongRangeOrdered() {
		return pyeongMin == null || pyeongMax == null || pyeongMin <= pyeongMax;
	}

	@AssertTrue
	public boolean isPriceEokRangeOrdered() {
		return priceEokMin == null || priceEokMax == null || priceEokMin <= priceEokMax;
	}

	@AssertTrue
	public boolean isAgeRangeOrdered() {
		return ageMin == null || ageMax == null || ageMin <= ageMax;
	}

	@AssertTrue
	public boolean isUnitRangeOrdered() {
		return unitMin == null || unitMax == null || unitMin <= unitMax;
	}
}
