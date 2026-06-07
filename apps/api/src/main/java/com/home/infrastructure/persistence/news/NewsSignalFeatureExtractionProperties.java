package com.home.infrastructure.persistence.news;

record NewsSignalFeatureExtractionProperties(boolean enabled, int limit) {

	NewsSignalFeatureExtractionProperties {
		if (limit < 1) {
			throw new IllegalArgumentException("limit must be positive");
		}
	}
}
