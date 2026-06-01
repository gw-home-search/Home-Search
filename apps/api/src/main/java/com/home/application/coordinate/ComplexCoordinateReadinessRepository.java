package com.home.application.coordinate;

import java.util.List;

public interface ComplexCoordinateReadinessRepository {

	List<Long> findPendingCaseParcelIds(int limit);

	void markCaseFailed(Long parcelId, String reason);
}
