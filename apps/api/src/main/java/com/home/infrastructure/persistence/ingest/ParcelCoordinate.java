package com.home.infrastructure.persistence.ingest;

import java.math.BigDecimal;

public record ParcelCoordinate(
	BigDecimal latitude,
	BigDecimal longitude
) {

	public ParcelCoordinate {
		if (latitude == null) {
			throw new IllegalArgumentException("latitude is required");
		}
		if (longitude == null) {
			throw new IllegalArgumentException("longitude is required");
		}
	}
}
