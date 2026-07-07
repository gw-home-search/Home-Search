package com.home.application.coordinate.display;

public record ComplexDisplayCoordinateProjectionResult(
	int processed,
	int buildingFootprint,
	int parcelFallback,
	int skipped,
	int missing
) {

	public static ComplexDisplayCoordinateProjectionResult empty() {
		return new ComplexDisplayCoordinateProjectionResult(0, 0, 0, 0, 0);
	}

	ComplexDisplayCoordinateProjectionResult plusBuildingFootprint() {
		return new ComplexDisplayCoordinateProjectionResult(
			processed + 1,
			buildingFootprint + 1,
			parcelFallback,
			skipped,
			missing
		);
	}

	ComplexDisplayCoordinateProjectionResult plusParcelFallback() {
		return new ComplexDisplayCoordinateProjectionResult(
			processed + 1,
			buildingFootprint,
			parcelFallback + 1,
			skipped,
			missing
		);
	}

	ComplexDisplayCoordinateProjectionResult plusSkipped() {
		return new ComplexDisplayCoordinateProjectionResult(
			processed + 1,
			buildingFootprint,
			parcelFallback,
			skipped + 1,
			missing
		);
	}

	ComplexDisplayCoordinateProjectionResult plusMissing() {
		return new ComplexDisplayCoordinateProjectionResult(
			processed + 1,
			buildingFootprint,
			parcelFallback,
			skipped,
			missing + 1
		);
	}
}
