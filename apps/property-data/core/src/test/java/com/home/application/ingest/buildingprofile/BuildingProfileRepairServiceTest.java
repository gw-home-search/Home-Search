package com.home.application.ingest.buildingprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.home.application.ingest.buildingregister.BuildingRegisterCollectCommand;
import com.home.application.ingest.buildingregister.BuildingRegisterCollectionResult;
import com.home.application.ingest.buildingregister.BuildingRegisterCollectionService;
import com.home.application.ingest.buildingregister.BuildingRegisterCollectionStatus;
import com.home.application.ingest.buildingregister.BuildingRegisterRequestBudget;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BuildingProfileRepairServiceTest {
    private static final UUID SOURCE = UUID.fromString("123e4567-e89b-12d3-a456-426614174300");
    private static final UUID COLLECTION = UUID.fromString("123e4567-e89b-12d3-a456-426614174301");
    private static final UUID REQUEST = UUID.fromString("123e4567-e89b-12d3-a456-426614174302");

    @Test
    @DisplayName("repair는 hierarchy reason이 있는 target에서 BASIC을 허용하고 완료 PNU를 재호출하지 않는다")
    void repairsOnlyPendingTargetsWithBasicOverviewEnabled() {
        BuildingRegisterCollectionService collector = mock(BuildingRegisterCollectionService.class);
        BuildingProfileRepairRepository repairs = mock(BuildingProfileRepairRepository.class);
        BuildingProfileSampleRepository samples = mock(BuildingProfileSampleRepository.class);
        given(repairs.freezeOrLoad(any()))
                .willReturn(List.of(
                        new BuildingProfileCollectTarget("1168010300101400001", 1),
                        new BuildingProfileCollectTarget("1168010300101400002", 1)));
        given(samples.completedPnus(COLLECTION)).willReturn(Set.of("1168010300101400001"));
        given(collector.collect(any(BuildingRegisterCollectCommand.class), any(BuildingRegisterRequestBudget.class)))
                .willReturn(new BuildingRegisterCollectionResult(
                        BuildingRegisterCollectionStatus.COLLECTED, 0, List.of(), List.of(), List.of(), Set.of()));
        given(samples.completeIfAllPnusCollected(COLLECTION)).willReturn(true);

        BuildingProfileRepairSummary summary =
                new BuildingProfileRepairService(collector, repairs, samples).repair(command());

        ArgumentCaptor<BuildingRegisterCollectCommand> collected =
                ArgumentCaptor.forClass(BuildingRegisterCollectCommand.class);
        verify(collector).collect(collected.capture(), any(BuildingRegisterRequestBudget.class));
        assertThat(collected.getValue().pnu()).isEqualTo("1168010300101400002");
        assertThat(collected.getValue().includeBasicOverview()).isTrue();
        assertThat(summary.targetCount()).isEqualTo(2);
        assertThat(summary.completed()).isTrue();
    }

    @Test
    @DisplayName("같은 transient provider failure가 3회면 추가 요청 전에 중단한다")
    void stopsBeforeFourthTransientAttempt() {
        BuildingRegisterCollectionService collector = mock(BuildingRegisterCollectionService.class);
        BuildingProfileRepairRepository repairs = mock(BuildingProfileRepairRepository.class);
        BuildingProfileSampleRepository samples = mock(BuildingProfileSampleRepository.class);
        given(repairs.freezeOrLoad(any()))
                .willReturn(List.of(new BuildingProfileCollectTarget("1168010300101400001", 1)));
        given(samples.completedPnus(COLLECTION)).willReturn(Set.of());
        given(repairs.transientFailureCount(COLLECTION, "1168010300101400001")).willReturn(3);

        BuildingProfileRepairService service = new BuildingProfileRepairService(collector, repairs, samples);

        assertThatThrownBy(() -> service.repair(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("three attempts");
        verify(collector, never())
                .collect(any(BuildingRegisterCollectCommand.class), any(BuildingRegisterRequestBudget.class));
    }

    private BuildingProfileRepairCommand command() {
        return new BuildingProfileRepairCommand(
                SOURCE, COLLECTION, REQUEST, LocalDate.of(2026, 7, 27), "PROFILE_REPAIR_V1", 20_000, 1);
    }
}
