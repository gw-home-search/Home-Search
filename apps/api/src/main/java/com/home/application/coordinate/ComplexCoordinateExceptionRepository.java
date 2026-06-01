package com.home.application.coordinate;

import java.util.List;
import java.util.Optional;

public interface ComplexCoordinateExceptionRepository {

	List<ComplexCoordinateCaseCandidate> findExceptionCaseCandidates(int limit);

	void saveCaseUpdate(ComplexCoordinateCaseUpdate update);

	Optional<ComplexCoordinateParcelTargets> findParcelTargets(Long parcelId);

	List<BuildingFootprintCandidate> findBuildingFootprintsByPnu(String pnu);

	void saveResolvedDisplayCoordinate(ResolvedDisplayCoordinate coordinate);
}
