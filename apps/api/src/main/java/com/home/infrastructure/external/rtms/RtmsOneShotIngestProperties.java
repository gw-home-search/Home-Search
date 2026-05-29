package com.home.infrastructure.external.rtms;

record RtmsOneShotIngestProperties(
	boolean enabled,
	String lawdCd,
	String dealYmd,
	Integer pageNo,
	boolean preflightOnly,
	String mode,
	Integer lookbackMonths
) {

	RtmsOneShotIngestProperties(
		boolean enabled,
		String lawdCd,
		String dealYmd,
		Integer pageNo,
		boolean preflightOnly
	) {
		this(enabled, lawdCd, dealYmd, pageNo, preflightOnly, "one-shot", 0);
	}

	RtmsApartmentTradeRequest request() {
		return new RtmsApartmentTradeRequest(lawdCd, dealYmd, pageNo);
	}

	RtmsIngestMode ingestMode() {
		return RtmsIngestMode.from(mode);
	}

	RtmsMonthlyRefreshPlan monthlyRefreshPlan() {
		return new RtmsMonthlyRefreshPlan(lawdCd, dealYmd, lookbackMonths == null ? 0 : lookbackMonths);
	}
}
