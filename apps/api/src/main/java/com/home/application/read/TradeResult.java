package com.home.application.read;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TradeResult(
	Long tradeId,
	LocalDate dealDate,
	BigDecimal exclArea,
	Long dealAmount,
	String aptDong,
	Integer floor
) {
}
