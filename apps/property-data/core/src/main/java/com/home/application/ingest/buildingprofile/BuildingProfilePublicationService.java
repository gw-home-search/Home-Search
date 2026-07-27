package com.home.application.ingest.buildingprofile;

import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class BuildingProfilePublicationService {
    private final BuildingProfilePublicationRepository repository;

    public BuildingProfilePublicationService(BuildingProfilePublicationRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public BuildingProfilePublicationSummary publish(BuildingProfilePublicationCommand command) {
        return repository.publish(command);
    }
}
