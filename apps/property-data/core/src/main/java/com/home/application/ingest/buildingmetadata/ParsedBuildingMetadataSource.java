package com.home.application.ingest.buildingmetadata;

import java.util.List;

import com.home.domain.complex.buildingmetadata.BuildingMetadataMatchPolicy.SourceCandidate;

public record ParsedBuildingMetadataSource(int totalCount, List<SourceCandidate> candidates) {
	public ParsedBuildingMetadataSource {
		candidates = candidates == null ? List.of() : List.copyOf(candidates);
	}
}
