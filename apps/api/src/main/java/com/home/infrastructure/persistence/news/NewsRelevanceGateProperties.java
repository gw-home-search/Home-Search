package com.home.infrastructure.persistence.news;

record NewsRelevanceGateProperties(boolean enabled, int limit) {

	NewsRelevanceGateProperties {
		if (limit < 1) {
			throw new IllegalArgumentException("limit must be positive");
		}
	}
}
