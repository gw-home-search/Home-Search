package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import java.util.Objects;

public record BuildingRegisterPageResponse(
        BuildingRegisterEndpoint endpoint,
        String pnu,
        int pageNo,
        int pageSize,
        int httpStatus,
        String body,
        long byteCount,
        String bodySha256,
        boolean oversized) {
    public BuildingRegisterPageResponse {
        Objects.requireNonNull(endpoint, "endpoint");
        if (pnu == null || !pnu.matches("[0-9]{19}")) throw new IllegalArgumentException("pnu must be 19 digits");
        if (pageNo <= 0 || pageSize <= 0) throw new IllegalArgumentException("page coordinates must be positive");
        if (httpStatus < 100 || httpStatus > 599) throw new IllegalArgumentException("invalid HTTP status");
        if (byteCount < 0) throw new IllegalArgumentException("byteCount must not be negative");
        if (bodySha256 == null || !bodySha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("bodySha256 must be lowercase SHA-256 hex");
        }
        if (oversized && body != null) throw new IllegalArgumentException("oversized response must not retain body");
    }

    public boolean httpSuccessful() {
        return httpStatus >= 200 && httpStatus < 300;
    }

    public boolean authenticationOrQuotaFailure() {
        return httpStatus == 401 || httpStatus == 403 || httpStatus == 429;
    }
}
