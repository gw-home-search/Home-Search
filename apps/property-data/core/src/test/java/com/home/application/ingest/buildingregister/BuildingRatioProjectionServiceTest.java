package com.home.application.ingest.buildingregister;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.domain.complex.buildingregister.BuildingRatioProjectionOutcome;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BuildingRatioProjectionServiceTest {
    private static final UUID COLLECTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174160");
    private static final UUID REQUEST_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174161");

    @Test
    void rejectsIncompleteCampaign() {
        FakeRepository repository = new FakeRepository();

        assertThatThrownBy(() -> new BuildingRatioProjectionService(repository)
                        .project(new BuildingRatioProjectCommand(COLLECTION_ID, REQUEST_ID, 10, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    void projectsSelectedCandidatesAndSummarizesOutcomes() {
        FakeRepository repository = new FakeRepository();
        repository.completed = true;

        var summary = new BuildingRatioProjectionService(repository)
                .project(new BuildingRatioProjectCommand(COLLECTION_ID, REQUEST_ID, 10, null, null));

        assertThat(summary.candidateCount()).isEqualTo(2);
        assertThat(summary.outcomes())
                .containsEntry(BuildingRatioProjectionOutcome.APPLIED, 1)
                .containsEntry(BuildingRatioProjectionOutcome.ALREADY_EQUAL, 1);
    }

    private static final class FakeRepository implements BuildingRatioProjectionRepository {
        boolean completed;

        @Override
        public boolean isCampaignCompleted(UUID collectionId) {
            return completed;
        }

        @Override
        public List<Long> findSelectedCandidateIds(UUID collectionId, Long fromComplexId, Long toComplexId, int limit) {
            return List.of(1L, 2L);
        }

        @Override
        public BuildingRatioProjectionOutcome project(UUID requestId, long candidateId) {
            return candidateId == 1
                    ? BuildingRatioProjectionOutcome.APPLIED
                    : BuildingRatioProjectionOutcome.ALREADY_EQUAL;
        }
    }
}
