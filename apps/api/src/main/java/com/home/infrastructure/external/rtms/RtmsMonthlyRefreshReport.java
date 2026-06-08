package com.home.infrastructure.external.rtms;

import java.util.List;

import com.home.application.ingest.trade.IngestResult;

record RtmsMonthlyRefreshReport(
	List<RtmsMonthlyRefreshRunSummary> runs
) {

	RtmsMonthlyRefreshReport {
		runs = runs == null ? List.of() : List.copyOf(runs);
	}

	IngestResult totalResult() {
		return runs.stream()
			.map(RtmsMonthlyRefreshRunSummary::ingestResult)
			.reduce(IngestResult.empty(), IngestResult::plus);
	}

	int totalPageCount() {
		return runs.stream()
			.mapToInt(RtmsMonthlyRefreshRunSummary::pageCount)
			.sum();
	}

	boolean hasNewData() {
		return runs.stream().anyMatch(RtmsMonthlyRefreshRunSummary::hasNewData);
	}
}
