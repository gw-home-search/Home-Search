package com.home.application.ingest;

/**
 * RTMS item의 지번 source에서 만든 PNU evidence입니다.
 */
public record RtmsJibunPnu(
	String rawJibun,
	String normalizedJibun,
	String sggCd,
	String umdCd,
	String landCode,
	String bonbun,
	String bubun,
	String derivedPnu,
	String pnuUnavailableReason
) {

	public static RtmsJibunPnu available(
		String rawJibun,
		String normalizedJibun,
		String sggCd,
		String umdCd,
		String landCode,
		String bonbun,
		String bubun
	) {
		return new RtmsJibunPnu(
			rawJibun,
			normalizedJibun,
			sggCd,
			umdCd,
			landCode,
			bonbun,
			bubun,
			sggCd + umdCd + landCode + bonbun + bubun,
			null
		);
	}

	public static RtmsJibunPnu unavailable(
		String rawJibun,
		String normalizedJibun,
		String sggCd,
		String umdCd,
		String reason
	) {
		return new RtmsJibunPnu(rawJibun, normalizedJibun, sggCd, umdCd, null, null, null, null, reason);
	}

	public boolean available() {
		return derivedPnu != null;
	}
}
