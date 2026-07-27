package com.home.application.ingest.buildingprofile;

public interface BuildingProfilePublicationRepository {
    BuildingProfilePublicationSummary publish(BuildingProfilePublicationCommand command);
}
