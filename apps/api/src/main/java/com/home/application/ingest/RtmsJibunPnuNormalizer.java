package com.home.application.ingest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RTMS item-level district code and jibun text를 display-safe PNU evidence로 정규화합니다.
 */
public final class RtmsJibunPnuNormalizer {

	private static final Pattern JIBUN_PATTERN = Pattern.compile("^(\\d+)(?:\\D+(\\d+))?$");
	private static final Pattern DISTRICT_CODE_PATTERN = Pattern.compile("^\\d{5}$");

	private RtmsJibunPnuNormalizer() {
	}

	public static RtmsJibunPnu normalize(OpenApiTradeItem item) {
		String rawJibun = trimToNull(item.jibun());
		String normalizedJibun = normalizeJibun(rawJibun);
		String sggCd = trimToNull(item.sggCd());
		String umdCd = trimToNull(item.umdCd());
		if (!validDistrictCode(sggCd)) {
			return RtmsJibunPnu.unavailable(rawJibun, normalizedJibun, sggCd, umdCd, "invalid sggCd");
		}
		if (!validDistrictCode(umdCd)) {
			return RtmsJibunPnu.unavailable(rawJibun, normalizedJibun, sggCd, umdCd, "invalid umdCd");
		}
		if (normalizedJibun == null) {
			return RtmsJibunPnu.unavailable(rawJibun, null, sggCd, umdCd, "jibun unavailable");
		}

		String landCode = normalizedJibun.contains("산") ? "2" : "1";
		String lotText = normalizedJibun.replace("산", "");
		Matcher matcher = JIBUN_PATTERN.matcher(lotText);
		if (!matcher.matches()) {
			return RtmsJibunPnu.unavailable(rawJibun, normalizedJibun, sggCd, umdCd, "invalid jibun");
		}
		String bon = matcher.group(1);
		String bu = matcher.group(2) == null ? "0" : matcher.group(2);
		if (bon.length() > 4 || bu.length() > 4) {
			return RtmsJibunPnu.unavailable(rawJibun, normalizedJibun, sggCd, umdCd, "jibun part too long");
		}
		return RtmsJibunPnu.available(rawJibun, normalizedJibun, sggCd, umdCd, landCode, pad4(bon), pad4(bu));
	}

	private static boolean validDistrictCode(String value) {
		return value != null && DISTRICT_CODE_PATTERN.matcher(value).matches();
	}

	private static String normalizeJibun(String value) {
		String text = trimToNull(value);
		return text == null ? null : text.replaceAll("\\s+", "");
	}

	private static String pad4(String value) {
		return "0".repeat(4 - value.length()) + value;
	}

	private static String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
