package com.home.application.coordinate.caseflow;

import java.util.List;
import java.util.Objects;

import com.home.application.coordinate.display.ResolvedDisplayCoordinate;

public record CoordinateResolutionCommitCommand(
	List<ResolvedDisplayCoordinate> coordinates,
	ComplexCoordinateCaseUpdate caseUpdate
) {

	public CoordinateResolutionCommitCommand {
		coordinates = List.copyOf(Objects.requireNonNull(coordinates, "coordinates are required"));
		Objects.requireNonNull(caseUpdate, "caseUpdate is required");
		if (caseUpdate.status().isResolved() && coordinates.isEmpty()) {
			throw new IllegalArgumentException("resolved case requires coordinates");
		}
		if (!caseUpdate.status().isResolved() && !coordinates.isEmpty()) {
			throw new IllegalArgumentException("unresolved case must not contain coordinates");
		}
	}
}
