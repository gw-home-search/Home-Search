package com.home.news.application;

import java.nio.file.Path;

public record HistoricalNewsNoteWriteResult(
	int candidateCount,
	int noteCount,
	Path outputRoot
) {
}
