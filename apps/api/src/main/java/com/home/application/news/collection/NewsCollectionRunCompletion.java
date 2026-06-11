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

	public static NewsCollectionRunCompletion from(
		long runId,
		NewsCollectionRunStatus status,
		OffsetDateTime finishedAt,
		CollectionMetrics collection,
		RelevanceMetrics relevance,
		ExtractionMetrics extraction,
		ExportMetrics export,
		NotificationResult notification,
		String failureReason
	) {
		return new NewsCollectionRunCompletion(
			runId,
			status,
			finishedAt,
			collection.keywordCount(),
			collection.readCount(),
			collection.observedCount(),
			collection.duplicateSkippedCount(),
			relevance.evaluatedCount(),
			relevance.keptCount(),
			relevance.skippedIrrelevantCount(),
			extraction.evaluatedCount(),
			extraction.extractedCount(),
			extraction.duplicateFeatureSkippedCount(),
			export.featureCount(),
			export.date(),
			export.path(),
			export.articleCount(),
			export.truncated(),
			notification.status(),
			notification.failureReason(),
			failureReason
		);
	}

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

	public record CollectionMetrics(int keywordCount, long readCount, long observedCount, long duplicateSkippedCount) {
	}

	public record RelevanceMetrics(long evaluatedCount, long keptCount, long skippedIrrelevantCount) {
	}

	public record ExtractionMetrics(long evaluatedCount, long extractedCount, long duplicateFeatureSkippedCount) {
	}

	public record ExportMetrics(int featureCount, String date, String path, int articleCount, boolean truncated) {

		public static ExportMetrics none() {
			return new ExportMetrics(0, null, null, 0, false);
		}
	}

	public record NotificationResult(NewsCollectionNotificationStatus status, String failureReason) {
	}
}
