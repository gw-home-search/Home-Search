package com.home.application.news;

import java.util.List;

public interface NewsArticleRelevanceRepository {

	List<NewsArticleRelevanceCandidate> findUnevaluatedObservedCandidates(int limit, String policyVersion);

	boolean saveDecisionIfAbsent(NewsArticleRelevanceDecision decision);

	boolean markSkippedIrrelevantIfObserved(long articleObservationId, String failureReason);
}
