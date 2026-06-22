package com.home.news.application;

import java.nio.file.Path;
import java.util.Map;

public record HistoricalNewsCsvNoteWriteResult(
	int fileCount,
	int generatedCount,
	int skippedFileCount,
	Map<String, Integer> skippedByReason,
	Path outputRoot
) {
}
