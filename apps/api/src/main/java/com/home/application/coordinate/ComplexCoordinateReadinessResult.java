package com.home.application.coordinate;

public record ComplexCoordinateReadinessResult(
	int staged,
	int pending,
	int skipped,
	int resolved,
	int ambiguous,
	int unavailable,
	int failed,
	int retried,
	int projectedBuildingFootprint,
	int projectedParcelFallback,
	int projectionSkipped,
	int projectionMissing
) {

	public static ComplexCoordinateReadinessResult empty() {
		return new ComplexCoordinateReadinessResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
	}
}
