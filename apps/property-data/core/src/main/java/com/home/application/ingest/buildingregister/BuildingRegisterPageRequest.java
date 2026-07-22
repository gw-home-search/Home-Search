package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import java.util.Objects;

public record BuildingRegisterPageRequest(BuildingRegisterEndpoint endpoint, String pnu, int pageNo, int pageSize) {
    public BuildingRegisterPageRequest {
        Objects.requireNonNull(endpoint, "endpoint");
        if (pnu == null || !pnu.matches("[0-9]{19}")) throw new IllegalArgumentException("pnu must be 19 digits");
        if (pageNo <= 0) throw new IllegalArgumentException("pageNo must be positive");
        if (pageSize != 10 && pageSize != 25 && pageSize != 50 && pageSize != 100) {
            throw new IllegalArgumentException("pageSize must be 10, 25, 50, or 100");
        }
    }
}
