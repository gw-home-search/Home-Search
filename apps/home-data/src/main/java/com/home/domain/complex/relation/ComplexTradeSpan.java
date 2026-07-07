package com.home.domain.complex.relation;

import java.time.LocalDate;

public record ComplexTradeSpan(
	Long complexId,
	String complexPk,
	String aptSeq,
	String name,
	LocalDate firstDeal,
	LocalDate lastDeal,
	long tradeCount,
	LocalDate useDate
) {

	public ComplexTradeSpan(
		Long complexId,
		String aptSeq,
		String name,
		LocalDate firstDeal,
		LocalDate lastDeal,
		long tradeCount,
		LocalDate useDate
	) {
		this(complexId, null, aptSeq, name, firstDeal, lastDeal, tradeCount, useDate);
	}

	boolean hasTradeSpan() {
		return tradeCount > 0 && firstDeal != null && lastDeal != null;
	}
}
