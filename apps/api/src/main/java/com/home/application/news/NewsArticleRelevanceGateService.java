package com.home.application.news;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

public class NewsArticleRelevanceGateService {

	private final NewsArticleRelevanceRepository repository;
	private final NewsArticleRelevancePolicy policy;
	private final Clock clock;

	public NewsArticleRelevanceGateService(
		NewsArticleRelevanceRepository repository,
		NewsArticleRelevancePolicy policy,
		Clock clock
	) {
		this.repository = Objects.requireNonNull(repository);
		this.policy = Objects.requireNonNull(policy);
		this.clock = Objects.requireNonNull(clock);
	}

	public NewsArticleRelevanceGateResult evaluateObserved(int limit) {
		if (limit < 1) {
			return NewsArticleRelevanceGateResult.empty();
		}
		List<NewsArticleRelevanceCandidate> candidates = repository.findUnevaluatedObservedCandidates(
			limit,
			policy.policyVersion()
		);

		long kept = 0;
		long reviewed = 0;
		long skippedIrrelevant = 0;
		long statusUpdated = 0;
		long decisionDuplicateSkipped = 0;
		OffsetDateTime evaluatedAt = OffsetDateTime.now(clock);
		for (NewsArticleRelevanceCandidate candidate : candidates) {
			NewsArticleRelevanceDecision decision = policy.evaluate(candidate, evaluatedAt);
			if (!repository.saveDecisionIfAbsent(decision)) {
				decisionDuplicateSkipped++;
				continue;
			}
			if (decision.decisionType() == NewsArticleRelevanceDecisionType.KEEP) {
				kept++;
			}
			else if (decision.decisionType() == NewsArticleRelevanceDecisionType.REVIEW) {
				reviewed++;
			}
			else if (decision.decisionType() == NewsArticleRelevanceDecisionType.SKIP_IRRELEVANT) {
				skippedIrrelevant++;
				if (repository.markSkippedIrrelevantIfObserved(
					decision.articleObservationId(),
					decision.skipFailureReason()
				)) {
					statusUpdated++;
				}
			}
		}
		return new NewsArticleRelevanceGateResult(
			candidates.size(),
			kept,
			reviewed,
			skippedIrrelevant,
			statusUpdated,
			decisionDuplicateSkipped
		);
	}
}
