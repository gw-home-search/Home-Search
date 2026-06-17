package com.home.rtmsloader;

import java.util.Locale;

public enum RtmsLoaderMode {

	INITIAL_LOAD(RtmsLoaderBoundary.INITIAL_LOAD_MODE),
	MONTHLY_BULK(RtmsLoaderBoundary.MONTHLY_BULK_MODE);

	private final String propertyValue;

	RtmsLoaderMode(String propertyValue) {
		this.propertyValue = propertyValue;
	}

	String propertyValue() {
		return propertyValue;
	}

	static RtmsLoaderMode from(String value) {
		if (value == null || value.isBlank()) {
			return MONTHLY_BULK;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		for (RtmsLoaderMode mode : values()) {
			if (mode.propertyValue.equals(normalized)) {
				return mode;
			}
		}
		throw new IllegalArgumentException("Unsupported RTMS loader mode: " + value);
	}
}
