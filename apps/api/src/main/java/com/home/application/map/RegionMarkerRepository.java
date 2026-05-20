package com.home.application.map;

import java.util.List;

import com.home.infrastructure.web.map.dto.RegionMarkerResponse;
import com.home.infrastructure.web.map.dto.RegionMarkersRequest;

public interface RegionMarkerRepository {

	List<RegionMarkerResponse> findRegionMarkers(RegionMarkersRequest request);
}
