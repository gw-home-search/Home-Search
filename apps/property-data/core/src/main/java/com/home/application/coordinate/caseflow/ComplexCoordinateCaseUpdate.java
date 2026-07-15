package com.home.application.coordinate.caseflow;

import com.home.domain.complex.relation.ComplexRelationConfidence;
import com.home.domain.complex.relation.ComplexRelationType;
import com.home.domain.coordinate.ComplexCoordinateCaseStatus;
import java.util.Objects;

public record ComplexCoordinateCaseUpdate(
        Long parcelId,
        ComplexCoordinateCaseStatus status,
        ComplexRelationType relationType,
        ComplexRelationConfidence relationConfidence,
        String reason) {

    public ComplexCoordinateCaseUpdate {
        Objects.requireNonNull(parcelId, "parcelId is required");
        Objects.requireNonNull(status, "status is required");
    }

    public ComplexCoordinateCaseUpdate(Long parcelId, ComplexCoordinateCaseStatus status, String reason) {
        this(parcelId, status, null, null, reason);
    }
}
