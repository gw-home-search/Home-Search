package com.home.application.ingest.trade;

import java.time.DateTimeException;
import java.time.LocalDate;

/**
 * RTMS source item에서 normalized trade command에 필요한 거래 필드를 해석한 application 값입니다.
 */
public record ParsedRtmsTrade(
	LocalDate dealDate,
	Long dealAmount,
	Integer floor
) {

	public static ParsedRtmsTrade from(OpenApiTradeItem item) {
		try {
			LocalDate dealDate = LocalDate.of(item.dealYear(), item.dealMonth(), item.dealDay());
			Long dealAmount = parseDealAmount(item.dealAmount());
			Integer floor = item.floor() != null && item.floor() == 0 ? null : item.floor();
			return new ParsedRtmsTrade(dealDate, dealAmount, floor);
		}
		catch (DateTimeException | NullPointerException exception) {
			throw new IllegalArgumentException("invalid deal date", exception);
		}
	}

	private static Long parseDealAmount(String rawAmount) {
		if (rawAmount == null || rawAmount.isBlank()) {
			throw new IllegalArgumentException("dealAmount is required");
		}
		try {
			long amount = Long.parseLong(rawAmount.replace(",", "").replaceAll("\\s+", ""));
			if (amount <= 0) {
				throw new IllegalArgumentException("dealAmount must be positive");
			}
			return amount;
		}
		catch (NumberFormatException exception) {
			throw new IllegalArgumentException("dealAmount must be numeric", exception);
		}
	}
}
