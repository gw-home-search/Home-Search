package com.home.application.map;

import java.util.List;

public interface MapUseCase {

	List<ComplexMarkerResult> getComplexMarkers(ComplexMarkerQuery query);

	List<RegionMarkerResult> getRegionMarkers(RegionMarkerQuery query);
}
