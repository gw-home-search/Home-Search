package com.home.application.coordinate.caseflow;

import com.home.domain.coordinate.ComplexCoordinateCaseStatus;

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
			pending + (status.isPending() ? 1 : 0),
			resolved + (status.isResolved() ? 1 : 0),
			ambiguous + (status.isAmbiguous() ? 1 : 0),
			unavailable + (status.isUnavailable() ? 1 : 0),
			failed + (status.isFailed() ? 1 : 0),
			skipped + (status.isSkipped() ? 1 : 0)
		);
	}
}
