package com.home.domain.complex.buildingprofile;

public final class BuildingProfileQualityPolicy {
    public BuildingProfileQualityTier classify(BuildingProfileQualityMetrics metrics) {
        if (!metrics.meaningVerified()) return BuildingProfileQualityTier.RAW_ONLY;
        if (metrics.invalidRate() > 0.001 || metrics.comparableConflictRate() > 0.005) {
            return BuildingProfileQualityTier.REJECT_FOR_PROJECTION;
        }
        boolean coverage =
                switch (metrics.scope()) {
                    case SITE -> metrics.pnuCoverage() >= 0.90 && metrics.projectableComplexReadiness() >= 0.90;
                    case BUILDING -> metrics.buildingCoverage() >= 0.90 && metrics.pnuCoverage() >= 0.90;
                    case HIERARCHY -> false;
                };
        return coverage ? BuildingProfileQualityTier.PROMOTE_CANDIDATE : BuildingProfileQualityTier.RETAIN_PROFILE;
    }
}
