package com.home.application.news.export;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;

public record NewsSignalObsidianExportCommand(
	Path outputRoot,
	LocalDate date,
	ZoneId zoneId,
	int maxRows
) {

	public NewsSignalObsidianExportCommand {
		Objects.requireNonNull(outputRoot, "outputRoot must not be null");
		Objects.requireNonNull(date, "date must not be null");
		Objects.requireNonNull(zoneId, "zoneId must not be null");
		if (outputRoot.toString().isBlank()) {
			throw new IllegalArgumentException("outputRoot must not be blank");
		}
		if (maxRows < 1) {
			throw new IllegalArgumentException("maxRows must be positive");
		}
	}

	public OffsetDateTime startInclusive() {
		return date.atStartOfDay(zoneId).toOffsetDateTime();
	}

	public OffsetDateTime endExclusive() {
		return date.plusDays(1).atStartOfDay(zoneId).toOffsetDateTime();
	}
}
