package com.home.application.map;

import java.util.List;

public interface RegionMarkerRepository {

	List<RegionMarkerResult> findRegionMarkers(RegionMarkerQuery query);
}
