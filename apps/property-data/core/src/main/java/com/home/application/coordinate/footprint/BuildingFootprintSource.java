package com.home.application.coordinate.footprint;

import java.util.List;

public interface BuildingFootprintSource {

	List<BuildingFootprintImportCandidate> fetchByPnu(String pnu);

	static BuildingFootprintSource unavailable() {
		return pnu -> List.of();
	}
}
