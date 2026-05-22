package com.home.infrastructure.external.rtms;

public record RtmsApartmentTradeRequest(
	String lawdCd,
	String dealYmd,
	Integer pageNo
) {

	public RtmsApartmentTradeRequest {
		if (!hasText(lawdCd)) {
			throw new IllegalArgumentException("lawdCd is required");
		}
		if (!hasText(dealYmd)) {
			throw new IllegalArgumentException("dealYmd is required");
		}
		lawdCd = lawdCd.trim();
		dealYmd = dealYmd.trim();
		pageNo = pageNo == null ? 1 : pageNo;
		if (pageNo < 1) {
			throw new IllegalArgumentException("pageNo must be greater than zero");
		}
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
