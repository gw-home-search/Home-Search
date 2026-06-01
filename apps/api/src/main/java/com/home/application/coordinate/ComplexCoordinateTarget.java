package com.home.application.coordinate;

import java.util.Objects;
import java.util.Set;

public record ComplexCoordinateTarget(
	Long complexId,
	String aptSeq,
	String name,
	Set<String> aptDongs
) {

	public ComplexCoordinateTarget {
		Objects.requireNonNull(complexId, "complexId is required");
		Objects.requireNonNull(name, "name is required");
		aptDongs = Set.copyOf(aptDongs == null ? Set.of() : aptDongs);
	}
}
