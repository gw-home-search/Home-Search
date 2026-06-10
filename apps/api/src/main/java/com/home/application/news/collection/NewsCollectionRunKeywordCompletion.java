package com.home.application.news.collection;

import java.time.OffsetDateTime;
import java.util.Objects;

import com.home.domain.news.NewsCollectionRunStatus;

public record NewsCollectionRunKeywordCompletion(
	long runKeywordId,
	NewsCollectionRunStatus status,
	OffsetDateTime finishedAt,
	long readCount,
	long observedCount,
	long duplicateSkippedCount,
	String failureReason
) {

	public NewsCollectionRunKeywordCompletion {
		if (runKeywordId < 1) {
			throw new IllegalArgumentException("runKeywordId must be positive");
		}
		status = Objects.requireNonNull(status, "status must not be null");
		if (!status.isTerminal()) {
			throw new IllegalArgumentException("status must be terminal");
		}
		finishedAt = Objects.requireNonNull(finishedAt, "finishedAt must not be null");
		if (readCount < 0 || observedCount < 0 || duplicateSkippedCount < 0) {
			throw new IllegalArgumentException("counts must not be negative");
		}
	}
}
