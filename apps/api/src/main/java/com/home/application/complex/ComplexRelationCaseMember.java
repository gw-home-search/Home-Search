package com.home.application.complex;

import java.time.LocalDate;
import java.util.Objects;

public record ComplexRelationCaseMember(
	Long complexId,
	String complexPk,
	String aptSeq,
	String name,
	LocalDate firstDeal,
	LocalDate lastDeal,
	long tradeCount,
	LocalDate useDate
) {

	public ComplexRelationCaseMember {
		Objects.requireNonNull(complexId, "complexId is required");
		Objects.requireNonNull(complexPk, "complexPk is required");
		Objects.requireNonNull(name, "name is required");
		if (tradeCount < 0) {
			throw new IllegalArgumentException("tradeCount must be non-negative");
		}
	}
}
