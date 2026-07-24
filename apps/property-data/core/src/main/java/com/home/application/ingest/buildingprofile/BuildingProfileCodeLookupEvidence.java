package com.home.application.ingest.buildingprofile;

import com.home.domain.complex.buildingprofile.BuildingProfileCodeComparisonStatus;
import com.home.domain.complex.buildingprofile.BuildingProfileLookupResult;
import java.util.Set;
import java.util.UUID;

public record BuildingProfileCodeLookupEvidence(
        UUID requestId,
        UUID importId,
        String originalPnu,
        String candidatePnu,
        BuildingProfileLookupResult oldResult,
        BuildingProfileLookupResult newResult,
        BuildingProfileCodeComparisonStatus comparisonStatus,
        Set<String> oldManagementKeys,
        Set<String> newManagementKeys) {}
