package com.home.domain.complex.buildingprofile;

import java.math.BigDecimal;

public record BuildingProfileAreaContribution(
        String managementKey, BigDecimal architecturalArea, BigDecimal floorRatioEstimateArea) {
    public BuildingProfileAreaContribution {
        if (managementKey == null || managementKey.isBlank())
            throw new IllegalArgumentException("managementKey is required");
    }
}
