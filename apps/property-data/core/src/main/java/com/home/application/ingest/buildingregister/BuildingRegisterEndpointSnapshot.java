package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;

public record BuildingRegisterEndpointSnapshot(
        long id, BuildingRegisterEndpoint endpoint, int pageSize, int attemptNo) {
    public BuildingRegisterEndpointSnapshot {
        if (id <= 0 || pageSize <= 0 || attemptNo <= 0) throw new IllegalArgumentException("invalid snapshot");
    }
}
