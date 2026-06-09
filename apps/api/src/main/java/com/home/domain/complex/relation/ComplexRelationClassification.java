package com.home.domain.complex.relation;

import java.util.List;
import java.util.Objects;

public record ComplexRelationClassification(
	ComplexRelationType type,
	List<ComplexTradeSpan> spans,
	String reason,
	ComplexRelationConfidence confidence
) {

	public ComplexRelationClassification {
		Objects.requireNonNull(type, "type is required");
		Objects.requireNonNull(confidence, "confidence is required");
		spans = List.copyOf(spans == null ? List.of() : spans);
	}

	public ComplexRelationClassification(ComplexRelationType type, List<ComplexTradeSpan> spans, String reason) {
		this(type, spans, reason, ComplexRelationConfidence.NONE);
	}
}
