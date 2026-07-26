package com.home.domain.complex.buildingprofile;

import java.math.BigDecimal;

public record BuildingProfileDecimalDecision(
        BigDecimal value,
        BuildingProfilePublicScope scope,
        BuildingProfilePublicQuality quality,
        BuildingProfileConflictStatus conflictStatus,
        boolean complete) {

    public boolean mayBackfillOperationalColumn() {
        return value != null
                && complete
                && scope == BuildingProfilePublicScope.COMPLEX
                && quality == BuildingProfilePublicQuality.VERIFIED
                && conflictStatus == BuildingProfileConflictStatus.NONE;
    }
}
