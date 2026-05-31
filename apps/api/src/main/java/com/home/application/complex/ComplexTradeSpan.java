package com.home.application.complex;

import java.time.LocalDate;

public record ComplexTradeSpan(
	Long complexId,
	String aptSeq,
	String name,
	LocalDate firstDeal,
	LocalDate lastDeal,
	long tradeCount,
	LocalDate useDate
) {

	boolean hasTradeSpan() {
		return tradeCount > 0 && firstDeal != null && lastDeal != null;
	}
}
