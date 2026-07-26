package com.home.application.ingest.buildingprofile;

import com.home.domain.complex.buildingprofile.BuildingProfileProjectionPolicy;

public interface BuildingProfileProjectionRepository {
    BuildingProfileProjectionSummary project(
            BuildingProfileProjectionCommand command, BuildingProfileProjectionPolicy policy);
}
