package com.home.application.coordinate.display;

import java.math.BigDecimal;
import java.util.Objects;

public record ComplexDisplayCoordinateCommand(
	Long complexId,
	Long buildingFootprintId,
	BigDecimal latitude,
	BigDecimal longitude,
	String coordinateSource,
	int confidence,
	String reason
) {

	public ComplexDisplayCoordinateCommand {
		Objects.requireNonNull(complexId, "complexId is required");
		Objects.requireNonNull(latitude, "latitude is required");
		Objects.requireNonNull(longitude, "longitude is required");
		Objects.requireNonNull(coordinateSource, "coordinateSource is required");
		if ("BUILDING_FOOTPRINT".equals(coordinateSource)) {
			Objects.requireNonNull(buildingFootprintId, "buildingFootprintId is required for building coordinates");
		}
		if (confidence < 0 || confidence > 100) {
			throw new IllegalArgumentException("confidence must be between 0 and 100");
		}
	}
}
