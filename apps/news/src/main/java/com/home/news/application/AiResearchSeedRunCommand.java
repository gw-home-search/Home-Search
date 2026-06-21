package com.home.news.application;

import java.time.LocalDate;

public record AiResearchSeedRunCommand(
	LocalDate periodStart,
	LocalDate periodEnd,
	String bucketListJson,
	int targetCandidatesPerBucket,
	String model,
	String promptVersion,
	String schemaVersion,
	String outputManifestHash
) {
}
