package com.home.infrastructure.web.read.dto;

public record TradeTrendResponse(
	String month,
	long avgAmount,
	int count,
	long minAmount,
	long maxAmount
) {
}
