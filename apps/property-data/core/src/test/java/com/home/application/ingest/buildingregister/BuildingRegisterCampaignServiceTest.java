package com.home.application.ingest.buildingregister;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.home.domain.complex.buildingregister.BuildingRatioEvaluation;
import com.home.domain.complex.buildingregister.BuildingRatioResolutionStatus;
import com.home.domain.complex.buildingregister.BuildingRegisterCollectionMode;
import com.home.domain.complex.buildingregister.BuildingRegisterCollectionStrategy;
import com.home.domain.complex.buildingregister.BuildingRegisterComplexMatch;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import com.home.domain.complex.buildingregister.BuildingRegisterMatchStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BuildingRegisterCampaignServiceTest {
    private static final UUID COLLECTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174180");
    private static final UUID REQUEST_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174181");

    @Test
    @DisplayName("건축물대장 수집 캠페인 처리를 검증한다")
    void collectsSharedPnuOnceAndKeepsBothMatchesNonProjectable() {
        BuildingRegisterCollectionService collection = mock(BuildingRegisterCollectionService.class);
        BuildingRegisterCampaignRepository campaigns = mock(BuildingRegisterCampaignRepository.class);
        BuildingRatioCandidateRepository candidates = mock(BuildingRatioCandidateRepository.class);
        var targets = List.of(target(1, "A"), target(2, "B"));
        given(campaigns.freezeOrLoad(any())).willReturn(targets);
        given(collection.collect(any()))
                .willReturn(new BuildingRegisterCollectionResult(
                        BuildingRegisterCollectionStatus.COLLECTED,
                        1,
                        List.of(recap()),
                        List.of(),
                        List.of(),
                        Set.of()));
        given(campaigns.recordMatch(any(), anyString(), anyInt(), any())).willReturn(10L, 11L);
        given(campaigns.sourceRecordIds(any(), anyString())).willReturn(Map.of("ROOT-1", 100L));
        given(campaigns.completeIfAllTargetsMatched(COLLECTION_ID)).willReturn(true);

        var summary = new BuildingRegisterCampaignService(collection, campaigns, candidates).collect(command());

        ArgumentCaptor<BuildingRegisterCollectCommand> collectCommand =
                ArgumentCaptor.forClass(BuildingRegisterCollectCommand.class);
        verify(collection).collect(collectCommand.capture());
        assertThat(collectCommand.getValue().pnuComplexCount()).isEqualTo(2);
        ArgumentCaptor<BuildingRegisterComplexMatch> match =
                ArgumentCaptor.forClass(BuildingRegisterComplexMatch.class);
        verify(campaigns, times(2)).recordMatch(any(), anyString(), anyInt(), match.capture());
        assertThat(match.getAllValues())
                .allSatisfy(value -> assertThat(value.projectable()).isFalse());
        ArgumentCaptor<BuildingRatioEvaluation> evaluation = ArgumentCaptor.forClass(BuildingRatioEvaluation.class);
        verify(candidates, times(2)).record(anyLong(), evaluation.capture(), any());
        assertThat(evaluation.getAllValues())
                .allSatisfy(value -> assertThat(value.fields().values())
                        .allSatisfy(field -> assertThat(field.status())
                                .isEqualTo(BuildingRatioResolutionStatus.SKIPPED_SHARED_SCOPE)));
        assertThat(summary.requestCount()).isOne();
        assertThat(summary.completed()).isTrue();
    }

    @Test
    @DisplayName("건축물대장 수집 캠페인 처리를 검증한다")
    void evaluatesStandaloneTitleMatchedByExistingManagementKey() {
        BuildingRegisterCollectionService collection = mock(BuildingRegisterCollectionService.class);
        BuildingRegisterCampaignRepository campaigns = mock(BuildingRegisterCampaignRepository.class);
        BuildingRatioCandidateRepository candidates = mock(BuildingRatioCandidateRepository.class);
        var target = new BuildingRegisterCampaignTarget(
                1, "1168010300101400001", "TITLE-1", Set.of("Sample"), Set.of(), Set.of());
        given(campaigns.freezeOrLoad(any())).willReturn(List.of(target));
        given(collection.collect(any()))
                .willReturn(new BuildingRegisterCollectionResult(
                        BuildingRegisterCollectionStatus.COLLECTED,
                        1,
                        List.of(),
                        List.of(record(BuildingRegisterEndpoint.TITLE, "TITLE-1", null, "3", "20", "80")),
                        List.of(),
                        Set.of()));
        given(campaigns.recordMatch(any(), anyString(), anyInt(), any())).willReturn(20L);
        given(campaigns.sourceRecordIds(any(), anyString())).willReturn(Map.of("TITLE-1", 200L));

        var summary = new BuildingRegisterCampaignService(collection, campaigns, candidates).collect(command());

        ArgumentCaptor<BuildingRatioEvaluation> evaluation = ArgumentCaptor.forClass(BuildingRatioEvaluation.class);
        verify(candidates).record(anyLong(), evaluation.capture(), any());
        assertThat(evaluation.getValue().fields().values())
                .allSatisfy(field -> assertThat(field.projectable()).isTrue());
        assertThat(summary.matchCount()).isOne();
    }

    @Test
    @DisplayName("건축물대장 수집 캠페인 처리를 검증한다")
    void evaluatesUniqueRecapWithoutReinterpretingExistingComplexKey() {
        BuildingRegisterCollectionService collection = mock(BuildingRegisterCollectionService.class);
        BuildingRegisterCampaignRepository campaigns = mock(BuildingRegisterCampaignRepository.class);
        BuildingRatioCandidateRepository candidates = mock(BuildingRatioCandidateRepository.class);
        var target = new BuildingRegisterCampaignTarget(
                1, "1168010300101400001", "ROOT-1", Set.of("Sample"), Set.of(), Set.of());
        given(campaigns.freezeOrLoad(any())).willReturn(List.of(target));
        given(collection.collect(any()))
                .willReturn(new BuildingRegisterCollectionResult(
                        BuildingRegisterCollectionStatus.COLLECTED,
                        1,
                        List.of(recap()),
                        List.of(),
                        List.of(),
                        Set.of()));
        given(campaigns.recordMatch(any(), anyString(), anyInt(), any())).willReturn(21L);
        given(campaigns.sourceRecordIds(any(), anyString())).willReturn(Map.of("ROOT-1", 201L));

        new BuildingRegisterCampaignService(collection, campaigns, candidates).collect(command());

        verify(candidates).record(anyLong(), any(), any());
    }

    @Test
    @DisplayName("건축물대장 수집 캠페인 처리를 검증한다")
    void recordsEveryUnresolvedHierarchyReasonWithoutCreatingCandidates() {
        assertHierarchyFailure(List.of(), BuildingRegisterMatchStatus.SOURCE_MISSING);
        assertHierarchyFailure(
                List.of(
                        record(BuildingRegisterEndpoint.TITLE, "TITLE-1", null, "3", "20", "80"),
                        record(BuildingRegisterEndpoint.TITLE, "TITLE-1", null, "3", "21", "80", "Other")),
                BuildingRegisterMatchStatus.SOURCE_CONFLICT);
        assertHierarchyFailure(
                List.of(
                        record(BuildingRegisterEndpoint.RECAP_TITLE, "ROOT-1", null, "1", "20", "80"),
                        record(BuildingRegisterEndpoint.RECAP_TITLE, "ROOT-2", null, "1", "20", "80")),
                BuildingRegisterMatchStatus.AMBIGUOUS_GENERATION);

        BuildingRegisterCollectionService collection = mock(BuildingRegisterCollectionService.class);
        BuildingRegisterCampaignRepository campaigns = mock(BuildingRegisterCampaignRepository.class);
        BuildingRatioCandidateRepository candidates = mock(BuildingRatioCandidateRepository.class);
        given(campaigns.freezeOrLoad(any())).willReturn(List.of(target(1, "A")));
        given(collection.collect(any()))
                .willReturn(new BuildingRegisterCollectionResult(
                        BuildingRegisterCollectionStatus.COLLECTED,
                        1,
                        List.of(recap()),
                        List.of(),
                        List.of(record(BuildingRegisterEndpoint.BASIC_OVERVIEW, "TITLE-1", "ROOT-1", "3", null, null)),
                        Set.of()));

        new BuildingRegisterCampaignService(collection, campaigns, candidates).collect(command());

        ArgumentCaptor<BuildingRegisterComplexMatch> match =
                ArgumentCaptor.forClass(BuildingRegisterComplexMatch.class);
        verify(campaigns).recordMatch(any(), anyString(), anyInt(), match.capture());
        assertThat(match.getValue().status()).isEqualTo(BuildingRegisterMatchStatus.INCOMPLETE_HIERARCHY);
        verifyNoInteractions(candidates);
    }

    @Test
    @DisplayName("건축물대장 수집 캠페인 처리를 검증한다")
    void requestBudgetExhaustionLeavesCampaignResumable() {
        BuildingRegisterCollectionService collection = mock(BuildingRegisterCollectionService.class);
        BuildingRegisterCampaignRepository campaigns = mock(BuildingRegisterCampaignRepository.class);
        BuildingRatioCandidateRepository candidates = mock(BuildingRatioCandidateRepository.class);
        given(campaigns.freezeOrLoad(any())).willReturn(List.of(target(1, "A")));
        given(collection.collect(any())).willThrow(new BuildingRegisterRequestBudgetExceededException(10));

        var summary = new BuildingRegisterCampaignService(collection, campaigns, candidates).collect(command());

        assertThat(summary.requestCount()).isEqualTo(10);
        assertThat(summary.matchCount()).isZero();
        verify(campaigns).completeIfAllTargetsMatched(COLLECTION_ID);
        verifyNoInteractions(candidates);
    }

    private void assertHierarchyFailure(
            List<BuildingRegisterRecordSnapshotCommand> records, BuildingRegisterMatchStatus expectedStatus) {
        BuildingRegisterCollectionService collection = mock(BuildingRegisterCollectionService.class);
        BuildingRegisterCampaignRepository campaigns = mock(BuildingRegisterCampaignRepository.class);
        BuildingRatioCandidateRepository candidates = mock(BuildingRatioCandidateRepository.class);
        given(campaigns.freezeOrLoad(any())).willReturn(List.of(target(1, "A")));
        given(collection.collect(any()))
                .willReturn(new BuildingRegisterCollectionResult(
                        BuildingRegisterCollectionStatus.COLLECTED, 1, List.of(), records, List.of(), Set.of()));

        new BuildingRegisterCampaignService(collection, campaigns, candidates).collect(command());

        ArgumentCaptor<BuildingRegisterComplexMatch> match =
                ArgumentCaptor.forClass(BuildingRegisterComplexMatch.class);
        verify(campaigns).recordMatch(any(), anyString(), anyInt(), match.capture());
        assertThat(match.getValue().status()).isEqualTo(expectedStatus);
        verifyNoInteractions(candidates);
    }

    private BuildingRegisterCampaignCommand command() {
        return new BuildingRegisterCampaignCommand(
                COLLECTION_ID,
                REQUEST_ID,
                LocalDate.of(2026, 7, 20),
                BuildingRegisterCollectionMode.MISSING,
                BuildingRegisterCollectionStrategy.ADAPTIVE,
                10,
                null,
                100L);
    }

    private BuildingRegisterCampaignTarget target(long id, String name) {
        return new BuildingRegisterCampaignTarget(id, "1168010300101400001", null, Set.of(name), Set.of(), Set.of());
    }

    private BuildingRegisterRecordSnapshotCommand recap() {
        return record(BuildingRegisterEndpoint.RECAP_TITLE, "ROOT-1", null, "1", "20", "80");
    }

    private BuildingRegisterRecordSnapshotCommand record(
            BuildingRegisterEndpoint endpoint,
            String managementKey,
            String parentManagementKey,
            String registerKindCode,
            String buildingCoverageRatio,
            String floorAreaRatio) {
        return record(
                endpoint,
                managementKey,
                parentManagementKey,
                registerKindCode,
                buildingCoverageRatio,
                floorAreaRatio,
                "Sample");
    }

    private BuildingRegisterRecordSnapshotCommand record(
            BuildingRegisterEndpoint endpoint,
            String managementKey,
            String parentManagementKey,
            String registerKindCode,
            String buildingCoverageRatio,
            String floorAreaRatio,
            String buildingName) {
        return new BuildingRegisterRecordSnapshotCommand(
                0,
                "1168010300101400001",
                endpoint,
                managementKey,
                parentManagementKey,
                "1",
                registerKindCode,
                "1",
                null,
                buildingName,
                null,
                "02000",
                new BigDecimal("1000"),
                new BigDecimal("200"),
                new BigDecimal("900"),
                new BigDecimal("800"),
                buildingCoverageRatio == null ? null : new BigDecimal(buildingCoverageRatio),
                floorAreaRatio == null ? null : new BigDecimal(floorAreaRatio),
                2,
                0,
                700,
                LocalDate.of(2015, 1, 1),
                LocalDate.of(2026, 7, 20));
    }
}
