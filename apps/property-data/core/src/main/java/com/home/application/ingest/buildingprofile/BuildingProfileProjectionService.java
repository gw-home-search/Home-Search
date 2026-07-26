package com.home.application.ingest.buildingprofile;

import com.home.domain.complex.buildingprofile.BuildingProfileProjectionPolicy;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class BuildingProfileProjectionService {
    private final BuildingProfileProjectionRepository repository;
    private final BuildingProfileProjectionPolicy policy = new BuildingProfileProjectionPolicy();

    public BuildingProfileProjectionService(BuildingProfileProjectionRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public BuildingProfileProjectionSummary project(BuildingProfileProjectionCommand command) {
        return repository.project(command, policy);
    }
}
