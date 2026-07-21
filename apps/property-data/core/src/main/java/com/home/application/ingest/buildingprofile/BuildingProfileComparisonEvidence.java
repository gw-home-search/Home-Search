package com.home.application.ingest.buildingprofile;

import com.home.domain.complex.buildingprofile.BuildingProfileAggregation;
import com.home.domain.complex.buildingprofile.BuildingProfileComparisonStatus;
import com.home.domain.complex.buildingprofile.BuildingProfileField;
import java.math.BigDecimal;

public record BuildingProfileComparisonEvidence(
        String scopeHash,
        BuildingProfileField field,
        BuildingProfileAggregation aggregation,
        BuildingProfileComparisonStatus status,
        String recapValue,
        String titleValue,
        BigDecimal difference,
        int contributorCount,
        int expectedContributorCount) {}
