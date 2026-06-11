package com.home.infrastructure.scheduling.news;

import java.util.StringJoiner;

import com.home.application.news.collection.NewsCollectionRunCompletion;
import com.home.application.news.observation.NewsArticleObservationIngestResult;

class NaverNewsDailyPipelineMessageFormatter {

	String format(NaverNewsDailyPipelineExecution execution) {
		NewsCollectionRunCompletion completion = execution.completion();
		StringJoiner message = new StringJoiner(System.lineSeparator());
		message.add("[News daily pipeline] " + title(execution));
		message.add(
			"status=" + execution.status()
				+ " runId=" + execution.runId()
				+ " keywordCount=" + completion.keywordCount()
				+ " read=" + completion.readCount()
				+ " observed=" + completion.observedCount()
				+ " duplicateSkipped=" + completion.duplicateSkippedCount()
				+ " relevanceKept=" + completion.relevanceKeptCount()
				+ " extracted=" + completion.extractedCount()
				+ " exportPath=" + nullToBlank(completion.exportPath())
		);
		for (NaverNewsDailyPipelineKeywordExecution keyword : execution.keywordExecutions()) {
			message.add(formatKeyword(keyword));
		}
		if (completion.failureReason() != null && !completion.failureReason().isBlank()) {
			message.add("failureReason=" + sanitizeSensitiveValues(completion.failureReason()));
		}
		return message.toString();
	}

	static String sanitizeSensitiveValues(String value) {
		if (value == null) {
			return null;
		}
		return value
			.replaceAll("(?i)(serviceKey=)[^&\\s]+", "$1[REDACTED]")
			.replaceAll("(?i)(service_key=)[^&\\s]+", "$1[REDACTED]")
			.replaceAll("(?i)(sourceKey=)[^&\\s]+", "$1[REDACTED]")
			.replaceAll("(?i)(source_key=)[^&\\s]+", "$1[REDACTED]")
			.replaceAll("(?i)(payload=)\\{[^}]*}", "$1[REDACTED]")
			.replaceAll("(?i)(payload=)[^&\\s]+", "$1[REDACTED]")
			.replaceAll("https://hooks\\.slack\\.com/services/[^\\s]+", "https://hooks.slack.com/services/[REDACTED]");
	}

	private String title(NaverNewsDailyPipelineExecution execution) {
		return switch (execution.status()) {
			case SKIPPED -> "수집 대상 없음";
			case FAILED -> "실패";
			case PARTIAL -> "부분 실패";
			case COMPLETED -> execution.completion().extractedCount() > 0 ? "뉴스 신호 저장" : "신규 신호 없음";
			case STARTED -> "진행 중";
		};
	}

	private String formatKeyword(NaverNewsDailyPipelineKeywordExecution keyword) {
		NewsArticleObservationIngestResult ingest = keyword.ingestResult();
		String line = "- keyword=" + keyword.keyword().queryText()
			+ " type=" + keyword.keyword().keywordType()
			+ " status=" + keyword.status()
			+ " read=" + ingest.read()
			+ " observed=" + ingest.observed()
			+ " duplicateSkipped=" + ingest.duplicateSkipped();
		if (keyword.failureReason() == null || keyword.failureReason().isBlank()) {
			return line;
		}
		return line + " failureReason=" + sanitizeSensitiveValues(keyword.failureReason());
	}

	private static String nullToBlank(String value) {
		return value == null ? "" : value;
	}
}
