package com.home.news.application;

import java.nio.file.Path;
import java.util.Map;

public record HistoricalNewsCsvShortlistWriteResult(
	int fileCount,
	int monthCount,
	int candidateCount,
	int skippedFileCount,
	Map<String, Integer> skippedByReason,
	Path outputRoot
) {
}
