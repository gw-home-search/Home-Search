package com.home.application.ingest.buildingprofile;

import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;

public interface BuildingProfilePageParser {
    BuildingProfileParsedPage parse(
            BuildingRegisterEndpoint endpoint, String pnu, int pageNo, int pageSize, String responseBody);
}
