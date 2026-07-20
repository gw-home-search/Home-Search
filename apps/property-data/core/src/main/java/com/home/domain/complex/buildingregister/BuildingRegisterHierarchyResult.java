package com.home.domain.complex.buildingregister;

import java.util.List;

public record BuildingRegisterHierarchyResult(
        BuildingRegisterHierarchyStatus status, List<BuildingRegisterSourceScope> scopes, String reason) {
    public BuildingRegisterHierarchyResult {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }
}
