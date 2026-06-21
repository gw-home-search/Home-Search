package com.home.news.application;

import java.nio.file.Path;
import java.util.Map;

public record HistoricalNewsNoteWriteResult(
	int candidateCount,
	int noteCount,
	int rejectedCount,
	Map<HistoricalNewsCandidateRejectReason, Integer> rejectedByReason,
	Path outputRoot
) {
}
