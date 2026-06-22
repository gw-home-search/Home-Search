package com.home.news.application;

public record RegionMonthSignalImportResult(
	long importRunId,
	int rowCount,
	int snapshotUpsertCount,
	int evidenceUpsertCount
) {
}
