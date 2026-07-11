package com.home.application.ingest.rtms;

import java.util.List;

import com.home.application.ingest.trade.IngestResult;

public record RtmsMonthlyRefreshReport(
	List<RtmsMonthlyRefreshRunSummary> runs
) {

	public RtmsMonthlyRefreshReport {
		runs = runs == null ? List.of() : List.copyOf(runs);
	}

	public IngestResult totalResult() {
		return runs.stream()
			.map(RtmsMonthlyRefreshRunSummary::ingestResult)
			.reduce(IngestResult.empty(), IngestResult::plus);
	}

	public int totalPageCount() {
		return runs.stream()
			.mapToInt(RtmsMonthlyRefreshRunSummary::pageCount)
			.sum();
	}

	public boolean hasNewData() {
		return runs.stream().anyMatch(RtmsMonthlyRefreshRunSummary::hasNewData);
	}
}
