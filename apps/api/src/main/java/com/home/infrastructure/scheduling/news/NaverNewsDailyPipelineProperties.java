package com.home.infrastructure.scheduling.news;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

record NaverNewsDailyPipelineProperties(
	boolean enabled,
	int maxKeywords,
	int display,
	String sort,
	int relevanceLimit,
	int featureExtractionLimit,
	Path obsidianOutputRoot,
	LocalDate obsidianDate,
	ZoneId obsidianZoneId,
	int obsidianMaxRows
) {

	NaverNewsDailyPipelineProperties {
		if (maxKeywords < 1) {
			throw new IllegalArgumentException("maxKeywords must be positive");
		}
		if (display < 1 || display > 100) {
			throw new IllegalArgumentException("display must be between 1 and 100");
		}
		sort = normalizeSort(sort);
		if (relevanceLimit < 1) {
			throw new IllegalArgumentException("relevanceLimit must be positive");
		}
		if (featureExtractionLimit < 1) {
			throw new IllegalArgumentException("featureExtractionLimit must be positive");
		}
		obsidianOutputRoot = Objects.requireNonNull(obsidianOutputRoot, "obsidianOutputRoot must not be null");
		obsidianZoneId = Objects.requireNonNull(obsidianZoneId, "obsidianZoneId must not be null");
		if (obsidianMaxRows < 1) {
			throw new IllegalArgumentException("obsidianMaxRows must be positive");
		}
		if (enabled && obsidianOutputRoot.toString().isBlank()) {
			throw new IllegalArgumentException("obsidianOutputRoot must be configured when daily news pipeline is enabled");
		}
	}

	private static String normalizeSort(String value) {
		if (value == null || value.isBlank()) {
			return "date";
		}
		String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
		if (!normalized.equals("date") && !normalized.equals("sim")) {
			throw new IllegalArgumentException("sort must be date or sim");
		}
		return normalized;
	}
}
