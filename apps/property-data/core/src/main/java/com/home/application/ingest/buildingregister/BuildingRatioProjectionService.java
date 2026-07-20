package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRatioProjectionOutcome;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class BuildingRatioProjectionService {
    private final BuildingRatioProjectionRepository repository;

    public BuildingRatioProjectionService(BuildingRatioProjectionRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public BuildingRatioProjectionSummary project(BuildingRatioProjectCommand command) {
        if (!repository.isCampaignCompleted(command.collectionId())) {
            throw new IllegalStateException("building register campaign must be COMPLETED before projection");
        }
        List<Long> candidates = repository.findSelectedCandidateIds(
                command.collectionId(), command.fromComplexId(), command.toComplexId(), command.maxTargets());
        EnumMap<BuildingRatioProjectionOutcome, Integer> outcomes = new EnumMap<>(BuildingRatioProjectionOutcome.class);
        for (long candidateId : candidates) {
            outcomes.merge(repository.project(command.requestId(), candidateId), 1, Integer::sum);
        }
        return new BuildingRatioProjectionSummary(candidates.size(), outcomes);
    }
}
