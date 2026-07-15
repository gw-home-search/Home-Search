package com.home.infrastructure.web.read.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.home.application.read.TradeResult;

public record TradeResponse(
	Long tradeId,
	LocalDate dealDate,
	BigDecimal exclArea,
	Long dealAmount,
	String aptDong,
	Integer floor
) {

	public static TradeResponse from(TradeResult result) {
		return new TradeResponse(
			result.tradeId(),
			result.dealDate(),
			result.exclArea(),
			result.dealAmount(),
			result.aptDong(),
			result.floor()
		);
	}
}
