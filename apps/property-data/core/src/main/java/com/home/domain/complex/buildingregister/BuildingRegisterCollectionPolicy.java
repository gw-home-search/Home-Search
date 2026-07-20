package com.home.domain.complex.buildingregister;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;

public final class BuildingRegisterCollectionPolicy {
    public BuildingRegisterFollowUpDecision afterRecap(
            BuildingRegisterCollectionStrategy strategy, List<BuildingRegisterRecord> recaps, int pnuComplexCount) {
        if (strategy == null) throw new IllegalArgumentException("collection strategy is required");
        if (pnuComplexCount <= 0) throw new IllegalArgumentException("PNU complex count must be positive");
        List<BuildingRegisterRecord> safeRecaps = recaps == null ? List.of() : List.copyOf(recaps);
        if (strategy == BuildingRegisterCollectionStrategy.FULL_HIERARCHY) {
            return new BuildingRegisterFollowUpDecision(true, true, EnumSet.allOf(BuildingRatioField.class));
        }
        if (safeRecaps.isEmpty()) {
            return new BuildingRegisterFollowUpDecision(true, false, EnumSet.allOf(BuildingRatioField.class));
        }
        if (safeRecaps.size() != 1 || pnuComplexCount != 1) {
            return new BuildingRegisterFollowUpDecision(true, true, EnumSet.allOf(BuildingRatioField.class));
        }
        BuildingRegisterRecord recap = safeRecaps.get(0);
        EnumSet<BuildingRatioField> missing = EnumSet.noneOf(BuildingRatioField.class);
        for (BuildingRatioField field : BuildingRatioField.values()) {
            if (!positive(field.directRatio(recap))) missing.add(field);
        }
        return new BuildingRegisterFollowUpDecision(!missing.isEmpty(), !missing.isEmpty(), missing);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
