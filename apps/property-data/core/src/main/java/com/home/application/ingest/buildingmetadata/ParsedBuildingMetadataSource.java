package com.home.application.ingest.buildingmetadata;

import com.home.domain.complex.buildingmetadata.BuildingMetadataMatchPolicy.SourceCandidate;
import java.util.List;

public record ParsedBuildingMetadataSource(int totalCount, List<SourceCandidate> candidates) {
    public ParsedBuildingMetadataSource {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
