package com.home.application.news;

import java.util.List;

public interface NewsSignalFeatureExtractionRepository {

	List<NewsSignalFeatureExtractionCandidate> findPendingCandidates(int limit, String extractionVersion);

	boolean saveFeatureIfAbsent(NewsSignalFeatureCommand command);

	boolean markFeaturedIfObserved(long articleObservationId);
}
