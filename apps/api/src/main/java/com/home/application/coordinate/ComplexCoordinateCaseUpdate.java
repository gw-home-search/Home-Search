package com.home.application.coordinate;

import java.util.Objects;

import com.home.application.complex.ComplexRelationConfidence;
import com.home.application.complex.ComplexRelationType;

public record ComplexCoordinateCaseUpdate(
	Long parcelId,
	ComplexCoordinateCaseStatus status,
	ComplexRelationType relationType,
	ComplexRelationConfidence relationConfidence,
	String reason
) {

	public ComplexCoordinateCaseUpdate {
		Objects.requireNonNull(parcelId, "parcelId is required");
		Objects.requireNonNull(status, "status is required");
	}

	public ComplexCoordinateCaseUpdate(Long parcelId, ComplexCoordinateCaseStatus status, String reason) {
		this(parcelId, status, null, null, reason);
	}
}
