package com.home.domain.complex.buildingregister;

import java.util.Set;

public record BuildingRegisterComplexTarget(
        long complexId,
        String existingManagementKey,
        Set<String> names,
        Set<String> tradeDongNames,
        Set<String> footprintDongNames) {
    public BuildingRegisterComplexTarget {
        if (complexId <= 0) throw new IllegalArgumentException("complexId must be positive");
        if (existingManagementKey != null && existingManagementKey.isBlank()) {
            throw new IllegalArgumentException("existingManagementKey must be null or nonblank");
        }
        names = names == null ? Set.of() : Set.copyOf(names);
        tradeDongNames = tradeDongNames == null ? Set.of() : Set.copyOf(tradeDongNames);
        footprintDongNames = footprintDongNames == null ? Set.of() : Set.copyOf(footprintDongNames);
    }
}
