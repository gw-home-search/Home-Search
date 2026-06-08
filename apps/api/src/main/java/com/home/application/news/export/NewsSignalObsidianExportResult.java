package com.home.application.news.export;

import java.nio.file.Path;
import java.time.LocalDate;

public record NewsSignalObsidianExportResult(
	LocalDate date,
	Path path,
	int featureCount,
	int articleCount,
	boolean truncated
) {
}
