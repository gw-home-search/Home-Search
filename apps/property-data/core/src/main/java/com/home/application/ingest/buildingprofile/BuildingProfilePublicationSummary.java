package com.home.application.ingest.buildingprofile;

import com.home.domain.complex.buildingprofile.BuildingProfilePublicationStatus;

public record BuildingProfilePublicationSummary(
        int siteCount,
        int buildingCount,
        int hierarchyCount,
        int evidenceCount,
        int summaryCount,
        String contentSha256,
        BuildingProfilePublicationStatus status,
        boolean alreadyCompleted) {}
