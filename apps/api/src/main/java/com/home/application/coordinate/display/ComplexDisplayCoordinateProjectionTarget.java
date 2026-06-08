package com.home.application.coordinate.display;

import java.math.BigDecimal;
import java.util.Objects;
import com.home.application.coordinate.caseflow.ComplexCoordinateCaseStatus;

public record ComplexDisplayCoordinateProjectionTarget(
	Long complexId,
	Long parcelId,
	BigDecimal parcelLatitude,
	BigDecimal parcelLongitude,
	int parcelComplexCount,
	ComplexCoordinateCaseStatus coordinateCaseStatus,
	String existingCoordinateSource,
	Long resolvedBuildingFootprintId,
	BigDecimal resolvedLatitude,
	BigDecimal resolvedLongitude,
	Integer resolvedConfidence,
	String resolvedReason
) {

	public ComplexDisplayCoordinateProjectionTarget {
		Objects.requireNonNull(complexId, "complexId is required");
		Objects.requireNonNull(parcelId, "parcelId is required");
		if (parcelComplexCount < 1) {
			throw new IllegalArgumentException("parcelComplexCount must be positive");
		}
	}

	public boolean hasExistingBuildingFootprintCoordinate() {
		return "BUILDING_FOOTPRINT".equals(existingCoordinateSource);
	}

	public boolean hasResolvedBuildingCoordinate() {
		return resolvedBuildingFootprintId != null && resolvedLatitude != null && resolvedLongitude != null;
	}

	public boolean hasParcelCoordinate() {
		return parcelLatitude != null && parcelLongitude != null;
	}
}
