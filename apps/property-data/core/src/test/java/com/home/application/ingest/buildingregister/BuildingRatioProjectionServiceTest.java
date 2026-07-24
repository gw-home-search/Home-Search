package com.home.application.ingest.buildingregister;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.domain.complex.buildingregister.BuildingRatioField;
import com.home.domain.complex.buildingregister.BuildingRatioProjectionOutcome;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildingRatioProjectionServiceTest {
    private static final UUID COLLECTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174160");
    private static final UUID REQUEST_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174161");

    @Test
    @DisplayName("건축물대장 비율 투영 결과를 검증한다")
    void rejectsIncompleteCampaign() {
        FakeRepository repository = new FakeRepository();

        assertThatThrownBy(() -> new BuildingRatioProjectionService(repository)
                        .project(new BuildingRatioProjectCommand(COLLECTION_ID, REQUEST_ID, 10, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    @DisplayName("건축물대장 비율 투영 결과를 검증한다")
    void projectsSelectedCandidatesAndSummarizesOutcomes() {
        FakeRepository repository = new FakeRepository();
        repository.completed = true;

        var summary = new BuildingRatioProjectionService(repository)
                .project(new BuildingRatioProjectCommand(COLLECTION_ID, REQUEST_ID, 10, null, null));

        assertThat(summary.candidateCount()).isEqualTo(2);
        assertThat(summary.outcomes())
                .containsEntry(BuildingRatioProjectionOutcome.APPLIED, 1)
                .containsEntry(BuildingRatioProjectionOutcome.SOURCE_MISSING, 1);
    }

    private static final class FakeRepository implements BuildingRatioProjectionRepository {
        boolean completed;

        @Override
        public boolean isCampaignCompleted(UUID collectionId) {
            return completed;
        }

        @Override
        public List<BuildingRatioProjectionTarget> findProjectionTargets(
                UUID collectionId, Long fromComplexId, Long toComplexId, int limit) {
            return List.of(
                    new BuildingRatioProjectionTarget(10, BuildingRatioField.BUILDING_COVERAGE_RATIO, 1L),
                    new BuildingRatioProjectionTarget(10, BuildingRatioField.FLOOR_AREA_RATIO, null));
        }

        @Override
        public BuildingRatioProjectionOutcome project(UUID requestId, BuildingRatioProjectionTarget target) {
            return target.candidateId() != null
                    ? BuildingRatioProjectionOutcome.APPLIED
                    : BuildingRatioProjectionOutcome.SOURCE_MISSING;
        }
    }
}
