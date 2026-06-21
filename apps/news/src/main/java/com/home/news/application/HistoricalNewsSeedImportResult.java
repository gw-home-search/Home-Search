package com.home.news.application;

public record HistoricalNewsSeedImportResult(
	int scannedCount,
	int importedCount,
	int duplicateCount,
	int skippedCount,
	int failedCount
) {
}
