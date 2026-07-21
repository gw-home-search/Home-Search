package com.home.domain.complex.buildingprofile;

public record BuildingProfileQualityMetrics(
        BuildingProfileScope scope,
        double sourceRecordCoverage,
        double buildingCoverage,
        double pnuCoverage,
        double projectableComplexReadiness,
        double invalidRate,
        double comparableConflictRate,
        boolean meaningVerified) {
    public BuildingProfileQualityMetrics {
        requireRate(sourceRecordCoverage);
        requireRate(buildingCoverage);
        requireRate(pnuCoverage);
        requireRate(projectableComplexReadiness);
        requireRate(invalidRate);
        requireRate(comparableConflictRate);
    }

    private static void requireRate(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("rate must be 0..1");
    }
}
