package com.home.infrastructure.web.map.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record RegionMarkersRequest(
	@NotNull Double swLat,
	@NotNull Double swLng,
	@NotNull Double neLat,
	@NotNull Double neLng,
	@NotBlank
	@Pattern(regexp = "si-do|si-gun-gu|eup-myeon-dong")
	String region
) {
}
