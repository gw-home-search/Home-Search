package com.home.application.ingest.buildingprofile;

import com.home.domain.complex.buildingprofile.BuildingProfileAssignmentStatus;

public record BuildingProfileAssignmentEvidence(
        long profileRecordId,
        String rootManagementKey,
        String scopeKey,
        BuildingProfileAssignmentStatus status,
        String reason) {}
