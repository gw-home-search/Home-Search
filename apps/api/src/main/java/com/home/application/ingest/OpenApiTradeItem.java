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
	String payload,
	String cancelDealType,
	String cancelDealDay,
	String registrationDate,
	String bonbun,
	String bubun
) {

	public OpenApiTradeItem(
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
		this(
			aptDong,
			aptName,
			aptSeq,
			dealAmount,
			dealDay,
			dealMonth,
			dealYear,
			exclArea,
			floor,
			jibun,
			sggCd,
			umdCd,
			payload,
			null,
			null,
			null,
			null,
			null
		);
	}

	public OpenApiTradeItem(
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
		String payload,
		String cancelDealType,
		String cancelDealDay,
		String registrationDate
	) {
		this(
			aptDong,
			aptName,
			aptSeq,
			dealAmount,
			dealDay,
			dealMonth,
			dealYear,
			exclArea,
			floor,
			jibun,
			sggCd,
			umdCd,
			payload,
			cancelDealType,
			cancelDealDay,
			registrationDate,
			null,
			null
		);
	}

	public OpenApiTradeItem {
		aptDong = trimToNull(aptDong);
		aptName = trimToNull(aptName);
		aptSeq = trimToNull(aptSeq);
		dealAmount = trimToNull(dealAmount);
		jibun = trimToNull(jibun);
		sggCd = trimToNull(sggCd);
		umdCd = trimToNull(umdCd);
		payload = trimToNull(payload);
		cancelDealType = trimToNull(cancelDealType);
		cancelDealDay = trimToNull(cancelDealDay);
		registrationDate = trimToNull(registrationDate);
		bonbun = trimToNull(bonbun);
		bubun = trimToNull(bubun);
	}

	public boolean isCanceled() {
		return "O".equalsIgnoreCase(cancelDealType);
	}

	private static String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
