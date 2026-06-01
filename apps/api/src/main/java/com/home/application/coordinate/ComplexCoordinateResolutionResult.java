package com.home.application.coordinate;

import java.util.Objects;

public record ComplexCoordinateResolutionResult(
	Long parcelId,
	ComplexCoordinateCaseStatus status,
	int resolvedCoordinates,
	String reason
) {

	public ComplexCoordinateResolutionResult {
		Objects.requireNonNull(parcelId, "parcelId is required");
		Objects.requireNonNull(status, "status is required");
		if (resolvedCoordinates < 0) {
			throw new IllegalArgumentException("resolvedCoordinates must be non-negative");
		}
	}
}
