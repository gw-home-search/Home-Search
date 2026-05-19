package com.home.application.ingest;

public record OpenApiTradeItem(
	String aptDong,
	String aptName,
	String aptSeq,
	String dealAmount,
	Integer dealDay,
	Integer dealMonth,
	Integer dealYear,
	Double exclArea,
	Integer floor,
	String jibun,
	String sggCd,
	String umdCd,
	String payload
) {

	public OpenApiTradeItem {
		aptDong = trimToNull(aptDong);
		aptName = trimToNull(aptName);
		aptSeq = trimToNull(aptSeq);
		dealAmount = trimToNull(dealAmount);
		jibun = trimToNull(jibun);
		sggCd = trimToNull(sggCd);
		umdCd = trimToNull(umdCd);
		payload = trimToNull(payload);
	}

	private static String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
