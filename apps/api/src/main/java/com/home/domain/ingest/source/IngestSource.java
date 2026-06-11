package com.home.domain.ingest.source;

/**
 * Raw ingest, match evidence, normalized trade에 남는 외부 원천 식별자입니다.
 */
public record IngestSource(String value) {

	public static IngestSource rtms() {
		return new IngestSource("RTMS");
	}

	public static IngestSource of(String value) {
		return new IngestSource(value);
	}

	public static IngestSource ofOrDefault(String value, String defaultValue) {
		return new IngestSource(hasText(value) ? value : defaultValue);
	}

	public IngestSource {
		if (!hasText(value)) {
			throw new IllegalArgumentException("source is required");
		}
		value = value.trim();
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
