package com.home.application.coordinate.identity;

import java.util.List;
import java.util.Objects;

public record ComplexCoordinateParcelTargets(
	Long parcelId,
	String pnu,
	List<ComplexCoordinateTarget> complexes
) {

	public ComplexCoordinateParcelTargets {
		Objects.requireNonNull(parcelId, "parcelId is required");
		Objects.requireNonNull(pnu, "pnu is required");
		complexes = List.copyOf(complexes == null ? List.of() : complexes);
	}
}
