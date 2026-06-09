package com.home.application.coordinate.footprint;

import java.math.BigDecimal;
import java.util.Objects;

public record CoordinateCenter(
	BigDecimal latitude,
	BigDecimal longitude
) {

	public CoordinateCenter {
		Objects.requireNonNull(latitude, "latitude is required");
		Objects.requireNonNull(longitude, "longitude is required");
	}
}
