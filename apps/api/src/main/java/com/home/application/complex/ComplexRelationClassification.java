package com.home.application.complex;

import java.util.List;
import java.util.Objects;

public record ComplexRelationClassification(
	ComplexRelationType type,
	List<ComplexTradeSpan> spans,
	String reason
) {

	public ComplexRelationClassification {
		Objects.requireNonNull(type, "type is required");
		spans = List.copyOf(spans == null ? List.of() : spans);
	}
}
