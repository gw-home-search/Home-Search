package com.home.application.map;

import java.util.List;

public interface ComplexMarkerRepository {

	List<ComplexMarkerResult> findComplexMarkers(ComplexMarkerQuery query);
}
