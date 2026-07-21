package com.home.application.ingest.buildingprofile;

import com.home.domain.complex.buildingprofile.BuildingProfileAssignmentStatus;

public record BuildingProfileComplexMatchEvidence(
        long complexId,
        String pnu,
        String scopeKey,
        BuildingProfileAssignmentStatus status,
        boolean projectable,
        String reason) {}
