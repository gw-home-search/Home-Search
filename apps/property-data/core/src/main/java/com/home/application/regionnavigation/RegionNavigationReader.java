package com.home.application.regionnavigation;

import com.home.application.read.ComplexSummaryResult;
import com.home.application.read.RegionDetailResult;
import com.home.application.read.RegionSummaryResult;
import java.util.List;
import java.util.Optional;

public interface RegionNavigationReader {

    List<RegionSummaryResult> findRootRegions();

    Optional<RegionDetailResult> findRegionDetail(Long regionId);

    Optional<List<ComplexSummaryResult>> findRegionComplexes(Long regionId, int limit, int offset);
}
