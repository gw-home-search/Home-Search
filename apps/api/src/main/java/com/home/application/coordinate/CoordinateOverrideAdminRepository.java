package com.home.application.coordinate;

import java.util.List;

public interface CoordinateOverrideAdminRepository {

	List<CoordinatePendingComplex> findPendingComplexes(int limit, int offset);

	CoordinatePendingSummary findPendingSummary();

	CoordinateOverrideApprovalResult approve(CoordinateOverrideApprovalCommand command);
}
