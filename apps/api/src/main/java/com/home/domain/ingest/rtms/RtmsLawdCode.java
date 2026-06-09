package com.home.domain.ingest.rtms;

import java.util.Optional;

/**
 * RTMS API와 raw ingest batch를 식별하는 5자리 법정동 시군구 코드입니다.
 */
public record RtmsLawdCode(String value) {

	private static final String REQUIRED_MESSAGE = "lawdCd is required";
	private static final String INVALID_MESSAGE = "lawdCd must be a 5 digit RTMS code";

	public static RtmsLawdCode of(String value) {
		return new RtmsLawdCode(value);
	}

	public static Optional<RtmsLawdCode> optional(String value) {
		return value == null || value.isBlank() ? Optional.empty() : Optional.of(of(value));
	}

	public RtmsLawdCode {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(REQUIRED_MESSAGE);
		}
		value = value.trim();
		if (!value.matches("\\d{5}")) {
			throw new IllegalArgumentException(INVALID_MESSAGE);
		}
	}
}
