package com.home.infrastructure.persistence.ingest;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.home.application.ingest.OpenApiTradeItem;

final class RtmsPnuBuilder {

	private static final Pattern JIBUN_PATTERN = Pattern.compile("(\\d+)(?:\\D+(\\d+))?");

	private RtmsPnuBuilder() {
	}

	static Optional<String> build(OpenApiTradeItem item) {
		String sggCd = trimToNull(item.sggCd());
		String umdCd = trimToNull(item.umdCd());
		if (sggCd == null || umdCd == null || sggCd.length() != 5 || umdCd.length() != 5) {
			return Optional.empty();
		}

		JibunParts jibun = parseJibun(item.jibun());
		if (jibun == null) {
			return Optional.empty();
		}

		String landCode = item.jibun() != null && item.jibun().contains("산") ? "2" : "1";
		return Optional.of(sggCd + umdCd + landCode + pad4(jibun.bon()) + pad4(jibun.bu()));
	}

	private static JibunParts parseJibun(String value) {
		String jibun = trimToNull(value);
		if (jibun == null) {
			return null;
		}
		Matcher matcher = JIBUN_PATTERN.matcher(jibun.replace("산", ""));
		if (!matcher.find()) {
			return null;
		}
		String bon = matcher.group(1);
		String bu = matcher.group(2) == null ? "0" : matcher.group(2);
		if (bon.length() > 4 || bu.length() > 4) {
			return null;
		}
		return new JibunParts(bon, bu);
	}

	private static String pad4(String value) {
		return "0".repeat(4 - value.length()) + value;
	}

	private static String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}

	private record JibunParts(String bon, String bu) {
	}
}
