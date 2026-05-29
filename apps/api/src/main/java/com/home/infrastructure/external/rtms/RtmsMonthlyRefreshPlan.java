package com.home.infrastructure.external.rtms;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.IntStream;

record RtmsMonthlyRefreshPlan(
	String lawdCd,
	String baseDealYmd,
	int lookbackMonths
) {

	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");
	private static final int MAX_LOOKBACK_MONTHS = 24;

	RtmsMonthlyRefreshPlan {
		RtmsApartmentTradeRequest baseRequest = new RtmsApartmentTradeRequest(lawdCd, baseDealYmd, 1);
		lawdCd = baseRequest.lawdCd();
		baseDealYmd = baseRequest.dealYmd();
		if (lookbackMonths < 0) {
			throw new IllegalArgumentException("lookbackMonths must not be negative");
		}
		if (lookbackMonths > MAX_LOOKBACK_MONTHS) {
			throw new IllegalArgumentException("lookbackMonths must be less than or equal to 24");
		}
	}

	List<RtmsApartmentTradeRequest> monthlyRequests() {
		YearMonth baseMonth = YearMonth.parse(baseDealYmd, FORMATTER);
		return IntStream.rangeClosed(0, lookbackMonths)
			.mapToObj(offset -> new RtmsApartmentTradeRequest(lawdCd, baseMonth.minusMonths(offset).format(FORMATTER), 1))
			.toList();
	}

	List<String> dealYmds() {
		return monthlyRequests().stream()
			.map(RtmsApartmentTradeRequest::dealYmd)
			.toList();
	}
}
