package com.home.application.ingest.buildingprofile;

import com.home.domain.complex.buildingprofile.BuildingProfileField;
import com.home.domain.complex.buildingprofile.BuildingProfileQualityTier;

public record BuildingProfileFieldQualityEvidence(
        BuildingProfileField field,
        String stratum,
        double sourceRecordCoverage,
        double buildingCoverage,
        double pnuCoverage,
        double projectableComplexReadiness,
        double operationalCompletion,
        double invalidRate,
        double conflictRate,
        double wilsonLow,
        double wilsonHigh,
        BuildingProfileQualityTier qualityTier,
        boolean meaningVerified,
        double numerator,
        double denominator) {}
