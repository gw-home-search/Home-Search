package com.home.application.news.collection;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;

import com.home.domain.news.NewsCollectionNotificationStatus;
import com.home.domain.news.NewsCollectionRunStatus;

public record NewsCollectionRunCompletion(
	long runId,
	NewsCollectionRunStatus status,
	OffsetDateTime finishedAt,
	int keywordCount,
	long readCount,
	long observedCount,
	long duplicateSkippedCount,
	long relevanceEvaluatedCount,
	long relevanceKeptCount,
	long relevanceSkippedIrrelevantCount,
	long extractionEvaluatedCount,
	long extractedCount,
	long duplicateFeatureSkippedCount,
	int exportFeatureCount,
	String exportDate,
	String exportPath,
	int exportArticleCount,
	boolean exportTruncated,
	NewsCollectionNotificationStatus notificationStatus,
	String notificationFailureReason,
	String failureReason
) {

	public NewsCollectionRunCompletion {
		if (runId < 1) {
			throw new IllegalArgumentException("runId must be positive");
		}
		status = Objects.requireNonNull(status, "status must not be null");
		if (!status.isTerminal()) {
			throw new IllegalArgumentException("status must be terminal");
		}
		finishedAt = Objects.requireNonNull(finishedAt, "finishedAt must not be null");
		if (keywordCount < 0) {
			throw new IllegalArgumentException("keywordCount must not be negative");
		}
		notificationStatus = Objects.requireNonNull(notificationStatus, "notificationStatus must not be null");
	}

	public LocalDate exportLocalDate() {
		if (exportDate == null || exportDate.isBlank()) {
			return null;
		}
		return LocalDate.parse(exportDate);
	}
}
