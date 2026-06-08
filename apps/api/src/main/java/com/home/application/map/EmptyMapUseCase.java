package com.home.application.map;

import java.util.List;

public class EmptyMapUseCase implements MapUseCase {

	@Override
	public List<ComplexMarkerResult> getComplexMarkers(ComplexMarkerQuery query) {
		return List.of();
	}

	@Override
	public List<RegionMarkerResult> getRegionMarkers(RegionMarkerQuery query) {
		return List.of();
	}
}
