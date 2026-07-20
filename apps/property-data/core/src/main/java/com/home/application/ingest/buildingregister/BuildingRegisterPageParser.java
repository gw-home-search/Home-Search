package com.home.application.ingest.buildingregister;

public interface BuildingRegisterPageParser {
    ParsedBuildingRegisterPage parse(BuildingRegisterPageResponse response);
}
