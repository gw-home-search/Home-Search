package com.home.application.news;

import java.util.List;
import java.util.Objects;

public class NewsSignalFeatureExtractionService {

	private final NewsSignalFeatureExtractionRepository repository;
	private final NewsSignalFeatureExtractionPolicy policy;

	public NewsSignalFeatureExtractionService(
		NewsSignalFeatureExtractionRepository repository,
		NewsSignalFeatureExtractionPolicy policy
	) {
		this.repository = Objects.requireNonNull(repository);
		this.policy = Objects.requireNonNull(policy);
	}

	public NewsSignalFeatureExtractionResult extractPending(int limit) {
		if (limit < 1) {
			return NewsSignalFeatureExtractionResult.empty();
		}
		List<NewsSignalFeatureExtractionCandidate> candidates = repository.findPendingCandidates(
			limit,
			policy.extractionVersion()
		);

		long extracted = 0;
		long statusUpdated = 0;
		long duplicateFeatureSkipped = 0;
		for (NewsSignalFeatureExtractionCandidate candidate : candidates) {
			NewsSignalFeatureCommand command = policy.extract(candidate);
			if (!repository.saveFeatureIfAbsent(command)) {
				duplicateFeatureSkipped++;
				continue;
			}
			extracted++;
			if (repository.markFeaturedIfObserved(candidate.articleObservationId())) {
				statusUpdated++;
			}
		}

		return new NewsSignalFeatureExtractionResult(
			candidates.size(),
			extracted,
			statusUpdated,
			duplicateFeatureSkipped
		);
	}
}
