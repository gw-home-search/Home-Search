package com.home.application.coordinate.caseflow;

import java.util.List;
import java.util.Optional;
import com.home.application.coordinate.display.ResolvedDisplayCoordinate;
import com.home.application.coordinate.footprint.BuildingFootprintCandidate;
import com.home.application.coordinate.footprint.BuildingFootprintImportCandidate;
import com.home.application.coordinate.identity.ComplexCoordinateParcelTargets;

public interface ComplexCoordinateExceptionRepository {

	List<ComplexCoordinateCaseCandidate> findExceptionCaseCandidates(int limit);

	void saveCaseUpdate(ComplexCoordinateCaseUpdate update);

	Optional<ComplexCoordinateParcelTargets> findParcelTargets(Long parcelId);

	List<BuildingFootprintCandidate> findBuildingFootprintsByPnu(String pnu);

	void saveBuildingFootprints(List<BuildingFootprintImportCandidate> footprints);

	void saveResolvedDisplayCoordinate(ResolvedDisplayCoordinate coordinate);
}
