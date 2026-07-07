package com.home.infrastructure.web.read.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TradeResponse(
	Long tradeId,
	LocalDate dealDate,
	BigDecimal exclArea,
	Long dealAmount,
	String aptDong,
	Integer floor
) {
}
