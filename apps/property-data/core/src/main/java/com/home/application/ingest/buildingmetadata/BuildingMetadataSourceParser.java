package com.home.application.ingest.buildingmetadata;

@FunctionalInterface
public interface BuildingMetadataSourceParser {
    ParsedBuildingMetadataSource parse(BuildingMetadataSourceResponse response);
}
