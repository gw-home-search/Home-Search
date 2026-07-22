package com.home.domain.complex.buildingprofile;

import java.util.Set;

public record BuildingProfileCollectionDecision(
        boolean fetchRecap,
        boolean fetchTitles,
        boolean fetchBasicOverview,
        Set<BuildingProfileHierarchyReason> basicOverviewReasons) {
    public BuildingProfileCollectionDecision {
        basicOverviewReasons = basicOverviewReasons == null ? Set.of() : Set.copyOf(basicOverviewReasons);
        if (fetchBasicOverview != !basicOverviewReasons.isEmpty()) {
            throw new IllegalArgumentException("BASIC fetch requires explicit hierarchy reasons");
        }
    }
}
