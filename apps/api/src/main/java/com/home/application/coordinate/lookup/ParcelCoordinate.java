package com.home.application.coordinate.lookup;

import java.math.BigDecimal;

public record ParcelCoordinate(
	BigDecimal latitude,
	BigDecimal longitude,
	String geometryWkt
) {

	public ParcelCoordinate(BigDecimal latitude, BigDecimal longitude) {
		this(latitude, longitude, null);
	}

	public ParcelCoordinate {
		if (latitude == null) {
			throw new IllegalArgumentException("latitude is required");
		}
		if (longitude == null) {
			throw new IllegalArgumentException("longitude is required");
		}
		geometryWkt = geometryWkt != null && !geometryWkt.isBlank() ? geometryWkt.trim() : null;
	}

}
