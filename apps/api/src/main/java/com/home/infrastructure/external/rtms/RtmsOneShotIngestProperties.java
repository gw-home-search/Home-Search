package com.home.infrastructure.external.rtms;

record RtmsOneShotIngestProperties(
	boolean enabled,
	String lawdCd,
	String dealYmd,
	Integer pageNo,
	boolean preflightOnly
) {

	RtmsApartmentTradeRequest request() {
		return new RtmsApartmentTradeRequest(lawdCd, dealYmd, pageNo);
	}
}
