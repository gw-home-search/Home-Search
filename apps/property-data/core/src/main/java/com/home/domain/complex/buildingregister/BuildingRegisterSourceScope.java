package com.home.domain.complex.buildingregister;

import java.util.Set;

public record BuildingRegisterSourceScope(
        String rootManagementKey,
        BuildingRatioScope scope,
        String buildingName,
        Set<String> dongNames,
        Set<String> expectedManagementKeys,
        boolean hierarchyComplete) {
    public BuildingRegisterSourceScope(
            String rootManagementKey,
            BuildingRatioScope scope,
            String buildingName,
            Set<String> dongNames,
            Set<String> expectedManagementKeys) {
        this(rootManagementKey, scope, buildingName, dongNames, expectedManagementKeys, true);
    }

    public BuildingRegisterSourceScope {
        if (rootManagementKey == null || rootManagementKey.isBlank()) {
            throw new IllegalArgumentException("rootManagementKey is required");
        }
        if (scope == null) throw new IllegalArgumentException("scope is required");
        rootManagementKey = rootManagementKey.trim();
        dongNames = dongNames == null ? Set.of() : Set.copyOf(dongNames);
        expectedManagementKeys = expectedManagementKeys == null ? Set.of() : Set.copyOf(expectedManagementKeys);
    }
}
