package com.home.application.ingest.buildingprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.home.application.ingest.buildingregister.BuildingRegisterCollectionResult;
import com.home.application.ingest.buildingregister.BuildingRegisterCollectionService;
import com.home.application.ingest.buildingregister.BuildingRegisterCollectionStatus;
import com.home.application.ingest.buildingregister.BuildingRegisterRecordSnapshotCommand;
import com.home.domain.complex.buildingprofile.BuildingProfileHierarchyReason;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BuildingProfileCollectionServiceTest {
    private static final String PNU = "1168010300101400001";

    @Test
    void collectsRecapAndTitleAndRecordsExplicitHierarchyReasons() {
        var collector = mock(BuildingRegisterCollectionService.class);
        var repository = new FakeSampleRepository();
        repository.targets = List.of(new BuildingProfileCollectTarget(PNU, 2));
        when(collector.collect(any(), any()))
                .thenReturn(result(
                        BuildingRegisterCollectionStatus.COLLECTED,
                        List.of(
                                record(BuildingRegisterEndpoint.RECAP_TITLE, "R1", null, "1", "0"),
                                record(BuildingRegisterEndpoint.RECAP_TITLE, "R2", null, "1", "0")),
                        List.of(record(BuildingRegisterEndpoint.TITLE, "T", null, "3", "1"))));

        BuildingProfileCollectSummary summary =
                new BuildingProfileCollectionService(collector, repository).collect(command(20));

        assertThat(summary.collectedCount()).isOne();
        assertThat(repository.reasons.get(PNU))
                .contains(
                        BuildingProfileHierarchyReason.MULTIPLE_COMPLEXES,
                        BuildingProfileHierarchyReason.MULTIPLE_RECAP_ROOTS,
                        BuildingProfileHierarchyReason.MISSING_PARENT);
        assertThat(summary.completed()).isTrue();
    }

    @Test
    void preservesIndependentOldAndNewCodeLookupEvidence() {
        var collector = mock(BuildingRegisterCollectionService.class);
        var repository = new FakeSampleRepository();
        repository.targets = List.of(new BuildingProfileCollectTarget(PNU, 1));
        repository.transition =
                Optional.of(new BuildingProfileCodeTransition(UUID.randomUUID(), "1168010300201400001"));
        when(collector.collect(any(), any()))
                .thenReturn(result(
                        BuildingRegisterCollectionStatus.COLLECTED,
                        List.of(record(BuildingRegisterEndpoint.RECAP_TITLE, "R", null, "1", "1")),
                        List.of()))
                .thenReturn(result(
                        BuildingRegisterCollectionStatus.COLLECTED,
                        List.of(record(BuildingRegisterEndpoint.RECAP_TITLE, "R", null, "1", "1")),
                        List.of()));

        new BuildingProfileCollectionService(collector, repository).collect(command(20));

        assertThat(repository.lookups).singleElement().satisfies(evidence -> {
            assertThat(evidence.originalPnu()).isEqualTo(PNU);
            assertThat(evidence.oldManagementKeys()).containsExactly("R");
            assertThat(evidence.newManagementKeys()).containsExactly("R");
        });
    }

    @Test
    void recordsProviderFailureWithoutMarkingPnuCollected() {
        var collector = mock(BuildingRegisterCollectionService.class);
        var repository = new FakeSampleRepository();
        repository.targets = List.of(new BuildingProfileCollectTarget(PNU, 1));
        when(collector.collect(any(), any()))
                .thenReturn(result(BuildingRegisterCollectionStatus.PROVIDER_FAILED, List.of(), List.of()));

        BuildingProfileCollectSummary summary =
                new BuildingProfileCollectionService(collector, repository).collect(command(1));

        assertThat(summary.failureCount()).isOne();
        assertThat(repository.failures).containsExactly("PROVIDER_FAILED");
        assertThat(repository.reasons).isEmpty();
    }

    private BuildingProfileCollectCommand command(int budget) {
        return new BuildingProfileCollectCommand(
                UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 7, 21), 1, "seed", budget, 1);
    }

    private BuildingRegisterCollectionResult result(
            BuildingRegisterCollectionStatus status,
            List<BuildingRegisterRecordSnapshotCommand> recaps,
            List<BuildingRegisterRecordSnapshotCommand> titles) {
        return new BuildingRegisterCollectionResult(status, 1, recaps, titles, List.of(), Set.of());
    }

    private BuildingRegisterRecordSnapshotCommand record(
            BuildingRegisterEndpoint endpoint, String key, String parent, String kind, String generation) {
        return new BuildingRegisterRecordSnapshotCommand(
                0,
                PNU,
                endpoint,
                key,
                parent,
                "1",
                kind,
                generation,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static final class FakeSampleRepository implements BuildingProfileSampleRepository {
        List<BuildingProfileCollectTarget> targets = List.of();
        Optional<BuildingProfileCodeTransition> transition = Optional.empty();
        Map<String, Set<BuildingProfileHierarchyReason>> reasons = new java.util.HashMap<>();
        List<String> failures = new ArrayList<>();
        List<BuildingProfileCodeLookupEvidence> lookups = new ArrayList<>();

        public List<BuildingProfileCollectTarget> freezeOrLoad(BuildingProfileCollectCommand command) {
            return targets;
        }

        public Set<String> completedPnus(UUID collectionId) {
            return Set.of();
        }

        public Optional<BuildingProfileCodeTransition> codeTransition(String originalPnu) {
            return transition;
        }

        public void recordCodeLookup(UUID collectionId, BuildingProfileCodeLookupEvidence evidence) {
            lookups.add(evidence);
        }

        public void recordCollected(UUID collectionId, String pnu, Set<BuildingProfileHierarchyReason> value) {
            reasons.put(pnu, value);
        }

        public void recordFailure(UUID collectionId, String pnu, String failureStatus) {
            failures.add(failureStatus);
        }

        public boolean completeIfAllPnusCollected(UUID collectionId) {
            return failures.isEmpty();
        }
    }
}
