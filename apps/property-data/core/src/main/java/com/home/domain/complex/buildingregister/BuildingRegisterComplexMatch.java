package com.home.domain.complex.buildingregister;

public record BuildingRegisterComplexMatch(
        long complexId,
        String rootManagementKey,
        BuildingRatioScope scope,
        BuildingRegisterMatchStatus status,
        BuildingRegisterMatchPath path,
        boolean projectable,
        String reason) {}
