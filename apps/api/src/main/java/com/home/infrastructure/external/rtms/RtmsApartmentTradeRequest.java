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
		if (!lawdCd.matches("\\d{5}")) {
			throw new IllegalArgumentException("lawdCd must be 5 digits");
		}
		if (!dealYmd.matches("\\d{6}") || !validMonth(dealYmd)) {
			throw new IllegalArgumentException("dealYmd must be YYYYMM");
		}
		pageNo = pageNo == null ? 1 : pageNo;
		if (pageNo < 1) {
			throw new IllegalArgumentException("pageNo must be greater than zero");
		}
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static boolean validMonth(String dealYmd) {
		int month = Integer.parseInt(dealYmd.substring(4, 6));
		return month >= 1 && month <= 12;
	}
}
