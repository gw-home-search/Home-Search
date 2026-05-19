package com.home.infrastructure.web.map.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record RegionMarkersRequest(
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
	@NotBlank
	@Pattern(regexp = "si-do|si-gun-gu|eup-myeon-dong")
	String region
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
