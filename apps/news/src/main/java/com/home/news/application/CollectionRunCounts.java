package com.home.news.application;

import com.home.domain.news.CollectionRunStatus;

public record CollectionRunCounts(
	CollectionRunStatus status,
	int keywordCount,
	int providerItemCount,
	int observedNewCount,
	int observedDuplicateCount,
	int featureCreatedCount,
	int featureSkippedCount,
	int failedCount,
	String failureReason
) {
}
