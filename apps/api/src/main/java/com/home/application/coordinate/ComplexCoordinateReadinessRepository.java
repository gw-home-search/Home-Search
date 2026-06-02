package com.home.application.coordinate;

import java.time.Instant;
import java.util.List;

public interface ComplexCoordinateReadinessRepository {

	List<Long> findPendingCaseParcelIds(int limit);

	void markCaseFailed(Long parcelId, String reason);

	default List<Long> findRetryableCaseParcelIds(int limit, Instant retryBefore) {
		return List.of();
	}
}
