package com.home.application.ingest.matching;

import java.util.List;
import java.util.Objects;

public record ComplexMatchCandidate(
	Long complexId,
	String complexPk,
	String tradeName,
	String name,
	String parcelPnu,
	List<String> normalizedAliases
) {

	public ComplexMatchCandidate {
		normalizedAliases = List.copyOf(Objects.requireNonNullElse(normalizedAliases, List.of()));
	}
}
