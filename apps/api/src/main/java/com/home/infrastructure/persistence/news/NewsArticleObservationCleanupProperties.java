package com.home.infrastructure.persistence.news;

import java.time.Duration;

record NewsArticleObservationCleanupProperties(boolean enabled, Duration retentionWindow) {

	NewsArticleObservationCleanupProperties {
		if (retentionWindow.isNegative()) {
			throw new IllegalArgumentException("retentionWindow must not be negative");
		}
	}
}
