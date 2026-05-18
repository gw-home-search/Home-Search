package com.home.application.map;

import java.util.List;

import com.home.infrastructure.web.map.dto.ComplexMarkerResponse;
import com.home.infrastructure.web.map.dto.ComplexMarkersRequest;
import com.home.infrastructure.web.map.dto.RegionMarkerResponse;
import com.home.infrastructure.web.map.dto.RegionMarkersRequest;

public interface MapUseCase {

	List<ComplexMarkerResponse> getComplexMarkers(ComplexMarkersRequest request);

	List<RegionMarkerResponse> getRegionMarkers(RegionMarkersRequest request);
}
