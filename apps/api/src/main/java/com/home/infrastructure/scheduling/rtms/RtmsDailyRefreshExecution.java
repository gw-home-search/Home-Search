package com.home.infrastructure.scheduling.rtms;

import java.util.List;

import com.home.application.ingest.trade.IngestResult;

record RtmsDailyRefreshExecution(
	List<RtmsDailyRefreshResult> results
) {

	RtmsDailyRefreshExecution {
		results = results == null ? List.of() : List.copyOf(results);
	}

	RtmsMonthlyRefreshRunStatus status() {
		if (results.isEmpty()) {
			return RtmsMonthlyRefreshRunStatus.COMPLETED;
		}
		boolean allFailed = results.stream()
			.allMatch(result -> result.status() == RtmsMonthlyRefreshRunStatus.FAILED);
		if (allFailed) {
			return RtmsMonthlyRefreshRunStatus.FAILED;
		}
		boolean anyIncomplete = results.stream()
			.anyMatch(result -> result.status() != RtmsMonthlyRefreshRunStatus.COMPLETED);
		return anyIncomplete ? RtmsMonthlyRefreshRunStatus.PARTIAL : RtmsMonthlyRefreshRunStatus.COMPLETED;
	}

	IngestResult totalResult() {
		return results.stream()
			.map(RtmsDailyRefreshResult::ingestResult)
			.reduce(IngestResult.empty(), IngestResult::plus);
	}

	int totalPageCount() {
		return results.stream()
			.mapToInt(RtmsDailyRefreshResult::pageCount)
			.sum();
	}

	boolean hasNewData() {
		return totalResult().normalizedInserted() > 0;
	}
}
