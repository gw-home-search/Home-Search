package com.home.application.ingest.buildingprofile;

import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import com.home.domain.complex.buildingregister.BuildingRegisterRawPageStatus;

public record BuildingProfileRawPage(
        long rawPageId,
        BuildingRegisterEndpoint endpoint,
        String pnu,
        int pageNo,
        int pageSize,
        BuildingRegisterRawPageStatus rawStatus,
        String providerStatus,
        String responseBody) {}
