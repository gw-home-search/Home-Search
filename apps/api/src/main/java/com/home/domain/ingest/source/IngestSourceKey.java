package com.home.domain.ingest.source;

/**
 * source와 함께 raw ingest 및 normalized trade dedupe를 고정하는 원천 row identity입니다.
 */
public record IngestSourceKey(String value) {

	public static IngestSourceKey of(String value) {
		return new IngestSourceKey(value);
	}

	public IngestSourceKey {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("sourceKey is required");
		}
		value = value.trim();
	}
}
