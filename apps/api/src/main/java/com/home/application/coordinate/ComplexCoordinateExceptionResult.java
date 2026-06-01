package com.home.application.coordinate;

public record ComplexCoordinateExceptionResult(
	int processed,
	int pending,
	int resolved,
	int ambiguous,
	int unavailable,
	int failed,
	int skipped
) {

	public static ComplexCoordinateExceptionResult empty() {
		return new ComplexCoordinateExceptionResult(0, 0, 0, 0, 0, 0, 0);
	}

	ComplexCoordinateExceptionResult plus(ComplexCoordinateCaseStatus status) {
		return new ComplexCoordinateExceptionResult(
			processed + 1,
			pending + (status == ComplexCoordinateCaseStatus.PENDING ? 1 : 0),
			resolved + (status == ComplexCoordinateCaseStatus.RESOLVED ? 1 : 0),
			ambiguous + (status == ComplexCoordinateCaseStatus.AMBIGUOUS ? 1 : 0),
			unavailable + (status == ComplexCoordinateCaseStatus.UNAVAILABLE ? 1 : 0),
			failed + (status == ComplexCoordinateCaseStatus.FAILED ? 1 : 0),
			skipped + (status == ComplexCoordinateCaseStatus.SKIPPED ? 1 : 0)
		);
	}
}
