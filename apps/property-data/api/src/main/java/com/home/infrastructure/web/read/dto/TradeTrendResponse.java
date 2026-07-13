package com.home.infrastructure.web.read.dto;

import com.home.application.read.TradeTrendPoint;

public record TradeTrendResponse(
	String month,
	long avgAmount,
	int count,
	long minAmount,
	long maxAmount
) {

	public static TradeTrendResponse from(TradeTrendPoint point) {
		return new TradeTrendResponse(
			point.month(),
			point.avgAmount(),
			point.count(),
			point.minAmount(),
			point.maxAmount()
		);
	}
}
