package com.home.domain.complex.buildingprofile;

import java.util.List;

public record BuildingProfileSampleSelection(
        String selectionSeed, List<BuildingProfileSampleEntry> entries, List<BuildingProfileStratumStats> strata) {
    public BuildingProfileSampleSelection {
        entries = List.copyOf(entries);
        strata = List.copyOf(strata);
    }
}
