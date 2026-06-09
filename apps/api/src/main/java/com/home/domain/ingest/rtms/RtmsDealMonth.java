package com.home.domain.ingest.rtms;

import java.util.Optional;

/**
 * RTMS 거래월입니다. 저장 값은 `yyyyMM` 문자열이며 월 범위는 01부터 12까지입니다.
 */
public record RtmsDealMonth(String value) {

	private static final String REQUIRED_MESSAGE = "dealYmd is required";
	private static final String INVALID_MESSAGE = "dealYmd must be yyyyMM";

	public static RtmsDealMonth of(String value) {
		return new RtmsDealMonth(value, INVALID_MESSAGE);
	}

	public static Optional<RtmsDealMonth> optional(String value) {
		return optional(value, INVALID_MESSAGE);
	}

	public static Optional<RtmsDealMonth> optional(String value, String invalidMessage) {
		return value == null || value.isBlank()
			? Optional.empty()
			: Optional.of(new RtmsDealMonth(value, invalidMessage));
	}

	private RtmsDealMonth(String value, String invalidMessage) {
		this(validate(value, invalidMessage));
	}

	public RtmsDealMonth {
		value = validate(value, INVALID_MESSAGE);
	}

	private static String validate(String value, String invalidMessage) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(REQUIRED_MESSAGE);
		}
		String normalized = value.trim();
		if (!normalized.matches("\\d{6}") || !validMonth(normalized)) {
			throw new IllegalArgumentException(invalidMessage);
		}
		return normalized;
	}

	private static boolean validMonth(String value) {
		int month = Integer.parseInt(value.substring(4, 6));
		return month >= 1 && month <= 12;
	}
}
