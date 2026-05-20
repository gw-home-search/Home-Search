package com.home.application.map;

import java.util.List;

import com.home.infrastructure.web.map.dto.ComplexMarkerResponse;
import com.home.infrastructure.web.map.dto.ComplexMarkersRequest;

public interface ComplexMarkerRepository {

	List<ComplexMarkerResponse> findComplexMarkers(ComplexMarkersRequest request);
}
