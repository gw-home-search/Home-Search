package com.home.infrastructure.persistence.news;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

record NewsSignalObsidianExportProperties(
	boolean enabled,
	Path outputRoot,
	LocalDate date,
	ZoneId zoneId,
	int maxRows
) {

	NewsSignalObsidianExportProperties {
		Objects.requireNonNull(zoneId, "zoneId must not be null");
		if (enabled) {
			Objects.requireNonNull(outputRoot, "outputRoot must not be null");
			if (outputRoot.toString().isBlank()) {
				throw new IllegalArgumentException("outputRoot must be configured when Obsidian export is enabled");
			}
		}
		if (maxRows < 1) {
			throw new IllegalArgumentException("maxRows must be positive");
		}
	}
}
