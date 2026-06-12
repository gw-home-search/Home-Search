package com.home.infrastructure.scheduling.rtms;

import java.util.StringJoiner;

import com.home.application.ingest.trade.IngestResult;

class RtmsDailyRefreshSlackMessageFormatter {

	String format(RtmsDailyRefreshExecution execution) {
		IngestResult total = execution.totalResult();
		StringJoiner message = new StringJoiner(System.lineSeparator());
		message.add("[RTMS daily refresh] " + title(execution));
		message.add(
			"status=" + execution.status()
				+ " lawdCount=" + execution.results().size()
				+ " pageCount=" + execution.totalPageCount()
				+ " read=" + total.read()
				+ " rawSaved=" + total.rawSaved()
				+ " normalizedInserted=" + total.normalizedInserted()
				+ " duplicateSkipped=" + total.duplicateSkipped()
				+ " canceledSkipped=" + total.canceledSkipped()
				+ " matchFailed=" + total.matchFailed()
				+ " parseFailed=" + total.parseFailed()
		);
		message.add(formatRegionSync(execution.regionSync()));
		for (RtmsDailyRefreshResult result : execution.results()) {
			message.add(formatResult(result));
		}
		return message.toString();
	}

	private String formatRegionSync(RtmsDailyRegionSyncResult result) {
		String line = "regionSync=" + result.status();
		if (result.failureReason() == null || result.failureReason().isBlank()) {
			return line;
		}
		return line + " failureReason=" + sanitizeSensitiveValues(result.failureReason());
	}

	String sanitizeSensitiveValues(String value) {
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

	private String title(RtmsDailyRefreshExecution execution) {
		return switch (execution.status()) {
			case FAILED -> "실패";
			case PARTIAL -> "부분 실패";
			case COMPLETED -> execution.hasNewData() ? "신규 실거래 저장" : "신규 저장 없음";
		};
	}

	private String formatResult(RtmsDailyRefreshResult result) {
		IngestResult ingest = result.ingestResult();
		String line = "- lawdCd=" + result.lawdCd()
			+ " dealYmds=" + result.dealYmds()
			+ " status=" + result.status()
			+ " pageCount=" + result.pageCount()
			+ " read=" + ingest.read()
			+ " rawSaved=" + ingest.rawSaved()
			+ " normalizedInserted=" + ingest.normalizedInserted()
			+ " duplicateSkipped=" + ingest.duplicateSkipped()
			+ " canceledSkipped=" + ingest.canceledSkipped()
			+ " matchFailed=" + ingest.matchFailed()
			+ " parseFailed=" + ingest.parseFailed()
			+ " runIds=" + result.runIds();
		if (result.failureReason() == null || result.failureReason().isBlank()) {
			return line;
		}
		return line + " failureReason=" + sanitizeSensitiveValues(result.failureReason());
	}
}
