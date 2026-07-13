package com.home.application.regionnavigation;

import java.util.List;
import java.util.Optional;

import com.home.application.read.ComplexSummaryResult;
import com.home.application.read.RegionDetailResult;
import com.home.application.read.RegionSummaryResult;

public interface RegionNavigationReader {

	List<RegionSummaryResult> findRootRegions();

	Optional<RegionDetailResult> findRegionDetail(Long regionId);

	Optional<List<ComplexSummaryResult>> findRegionComplexes(Long regionId, int limit, int offset);
}
