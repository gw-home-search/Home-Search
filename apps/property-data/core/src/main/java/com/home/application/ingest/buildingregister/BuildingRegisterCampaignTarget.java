package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRegisterComplexTarget;
import java.util.Set;

public record BuildingRegisterCampaignTarget(
        long complexId,
        String pnu,
        String existingManagementKey,
        Set<String> names,
        Set<String> tradeDongNames,
        Set<String> footprintDongNames) {
    public BuildingRegisterCampaignTarget {
        names = names == null ? Set.of() : Set.copyOf(names);
        tradeDongNames = tradeDongNames == null ? Set.of() : Set.copyOf(tradeDongNames);
        footprintDongNames = footprintDongNames == null ? Set.of() : Set.copyOf(footprintDongNames);
    }

    public BuildingRegisterComplexTarget matchTarget() {
        return new BuildingRegisterComplexTarget(
                complexId, existingManagementKey, names, tradeDongNames, footprintDongNames);
    }
}
