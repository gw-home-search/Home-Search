package com.home.application.coordinate.caseflow;

import java.util.Objects;

public record ComplexCoordinateCaseCandidate(Long parcelId) {

	public ComplexCoordinateCaseCandidate {
		Objects.requireNonNull(parcelId, "parcelId is required");
	}
}
