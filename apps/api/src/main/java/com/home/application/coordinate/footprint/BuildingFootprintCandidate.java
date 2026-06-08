package com.home.application.coordinate.footprint;

import java.math.BigDecimal;
import java.util.Objects;

public record BuildingFootprintCandidate(
	Long id,
	String pnu,
	String buildingName,
	String dongName,
	BigDecimal latitude,
	BigDecimal longitude
) {

	public BuildingFootprintCandidate {
		Objects.requireNonNull(id, "id is required");
		Objects.requireNonNull(pnu, "pnu is required");
		Objects.requireNonNull(latitude, "latitude is required");
		Objects.requireNonNull(longitude, "longitude is required");
	}
}
