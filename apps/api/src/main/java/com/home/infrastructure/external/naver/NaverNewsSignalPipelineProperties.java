package com.home.infrastructure.external.naver;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

record NaverNewsSignalPipelineProperties(
	boolean enabled,
	int relevanceLimit,
	int featureExtractionLimit,
	Path obsidianOutputRoot,
	LocalDate obsidianDate,
	ZoneId obsidianZoneId,
	int obsidianMaxRows
) {

	NaverNewsSignalPipelineProperties {
		Objects.requireNonNull(obsidianOutputRoot, "obsidianOutputRoot must not be null");
		Objects.requireNonNull(obsidianZoneId, "obsidianZoneId must not be null");
		if (relevanceLimit < 1) {
			throw new IllegalArgumentException("relevanceLimit must be positive");
		}
		if (featureExtractionLimit < 1) {
			throw new IllegalArgumentException("featureExtractionLimit must be positive");
		}
		if (obsidianMaxRows < 1) {
			throw new IllegalArgumentException("obsidianMaxRows must be positive");
		}
		if (enabled && obsidianOutputRoot.toString().isBlank()) {
			throw new IllegalArgumentException("obsidianOutputRoot must be configured when news pipeline is enabled");
		}
	}
}
