package com.home.application.ingest.buildingregister;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.home.domain.complex.buildingregister.BuildingRatioEvaluation;
import com.home.domain.complex.buildingregister.BuildingRatioResolutionStatus;
import com.home.domain.complex.buildingregister.BuildingRatioScope;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BuildingRegisterCampaignServiceTest {
    private static final UUID COLLECTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174180");
    private static final UUID REQUEST_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174181");

    @Test
    @DisplayName("미완료 캠페인 재개 시 완전히 평가된 PNU는 다시 수집하지 않는다")
    void skipsFullyMatchedPnuWhileResumingIncompleteCampaign() {
        BuildingRegisterCollectionService collection = mock(BuildingRegisterCollectionService.class);
        BuildingRegisterCampaignRepository campaigns = mock(BuildingRegisterCampaignRepository.class);
        BuildingRatioCandidateRepository candidates = mock(BuildingRatioCandidateRepository.class);
        var completed = target(1, "1168010300101400001", "Completed");
        var pending = target(2, "1168010300101400002", "Pending");
        given(campaigns.freezeOrLoad(any())).willReturn(List.of(completed, pending));
        given(campaigns.isCompleted(COLLECTION_ID)).willReturn(false);
        given(campaigns.fullyMatchedPnus(COLLECTION_ID)).willReturn(Set.of(completed.pnu()));
        given(collection.collect(any()))
                .willReturn(new BuildingRegisterCollectionResult(
                        BuildingRegisterCollectionStatus.COLLECTED,
                        1,
                        List.of(recap()),
                        List.of(),
                        List.of(),
                        Set.of()));
        given(campaigns.recordMatch(any(), anyString(), anyInt(), any())).willReturn(10L);
        given(campaigns.sourceRecordIds(any(), anyString())).willReturn(Map.of("ROOT-1", 100L));

        var summary = new BuildingRegisterCampaignService(collection, campaigns, candidates).collect(command());

        ArgumentCaptor<BuildingRegisterCollectCommand> collectCommand =
                ArgumentCaptor.forClass(BuildingRegisterCollectCommand.class);
        verify(collection).collect(collectCommand.capture());
        assertThat(collectCommand.getValue().pnu()).isEqualTo(pending.pnu());
        assertThat(summary.requestCount()).isOne();
        assertThat(summary.matchCount()).isOne();
    }

    @Test
    @DisplayName("완료 캠페인은 저장된 raw 재평가를 위해 기존 PNU를 다시 평가한다")
    void reevaluatesMatchedPnuForCompletedCampaign() {
        BuildingRegisterCollectionService collection = mock(BuildingRegisterCollectionService.class);
        BuildingRegisterCampaignRepository campaigns = mock(BuildingRegisterCampaignRepository.class);
        BuildingRatioCandidateRepository candidates = mock(BuildingRatioCandidateRepository.class);
        var target = target(1, "1168010300101400001", "Completed");
        given(campaigns.freezeOrLoad(any())).willReturn(List.of(target));
        given(campaigns.isCompleted(COLLECTION_ID)).willReturn(true);
        given(collection.collect(any()))
                .willReturn(new BuildingRegisterCollectionResult(
                        BuildingRegisterCollectionStatus.COLLECTED,
                        0,
                        List.of(recap()),
                        List.of(),
                        List.of(),
                        Set.of()));
        given(campaigns.recordMatch(any(), anyString(), anyInt(), any())).willReturn(10L);
        given(campaigns.sourceRecordIds(any(), anyString())).willReturn(Map.of("ROOT-1", 100L));

        var summary = new BuildingRegisterCampaignService(collection, campaigns, candidates).collect(command());

        verify(collection).collect(any());
        verify(campaigns, never()).fullyMatchedPnus(COLLECTION_ID);
        assertThat(summary.matchCount()).isOne();
    }

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
    @DisplayName("여러 단지가 공유하는 standalone 표제부는 후보 평가 없이 모호 상태로 남긴다")
    void recordsSharedStandaloneTitleAsAmbiguousWithoutEvaluation() {
        BuildingRegisterCollectionService collection = mock(BuildingRegisterCollectionService.class);
        BuildingRegisterCampaignRepository campaigns = mock(BuildingRegisterCampaignRepository.class);
        BuildingRatioCandidateRepository candidates = mock(BuildingRatioCandidateRepository.class);
        var targets = List.of(target(1, "A"), target(2, "B"));
        given(campaigns.freezeOrLoad(any())).willReturn(targets);
        given(collection.collect(any()))
                .willReturn(new BuildingRegisterCollectionResult(
                        BuildingRegisterCollectionStatus.COLLECTED,
                        1,
                        List.of(),
                        List.of(record(BuildingRegisterEndpoint.TITLE, "TITLE-1", null, "3", "20", "80")),
                        List.of(),
                        Set.of()));
        given(campaigns.recordMatch(any(), anyString(), anyInt(), any())).willReturn(22L, 23L);

        var summary = new BuildingRegisterCampaignService(collection, campaigns, candidates).collect(command());

        ArgumentCaptor<BuildingRegisterComplexMatch> match =
                ArgumentCaptor.forClass(BuildingRegisterComplexMatch.class);
        verify(campaigns, times(2)).recordMatch(any(), anyString(), anyInt(), match.capture());
        assertThat(match.getAllValues()).allSatisfy(value -> {
            assertThat(value.status()).isEqualTo(BuildingRegisterMatchStatus.AMBIGUOUS);
            assertThat(value.scope()).isEqualTo(BuildingRatioScope.STANDALONE_TITLE);
            assertThat(value.projectable()).isFalse();
        });
        verifyNoInteractions(candidates);
        assertThat(summary.matchCount()).isEqualTo(2);
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

    @Test
    @DisplayName("병렬 수집은 동시 실행 수와 전체 request budget을 함께 제한한다")
    void boundsParallelCollectionWithoutExceedingRequestBudget() {
        BuildingRegisterCollectionService collection = mock(BuildingRegisterCollectionService.class);
        BuildingRegisterCampaignRepository campaigns = mock(BuildingRegisterCampaignRepository.class);
        BuildingRatioCandidateRepository candidates = mock(BuildingRatioCandidateRepository.class);
        given(campaigns.freezeOrLoad(any()))
                .willReturn(List.of(
                        target(1, "1168010300101400001", "A"),
                        target(2, "1168010300101400002", "B"),
                        target(3, "1168010300101400003", "C"),
                        target(4, "1168010300101400004", "D"),
                        target(5, "1168010300101400005", "E")));
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch threeStarted = new CountDownLatch(3);
        given(collection.collect(any())).willAnswer(invocation -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            threeStarted.countDown();
            threeStarted.await(1, TimeUnit.SECONDS);
            active.decrementAndGet();
            return new BuildingRegisterCollectionResult(
                    BuildingRegisterCollectionStatus.PROVIDER_FAILED, 1, List.of(), List.of(), List.of(), Set.of());
        });

        var summary = new BuildingRegisterCampaignService(collection, campaigns, candidates).collect(command(5, 3));

        assertThat(maximum).hasValue(3);
        assertThat(summary.requestCount()).isEqualTo(5);
        verify(collection, times(5)).collect(any());
        ArgumentCaptor<BuildingRegisterCollectCommand> command =
                ArgumentCaptor.forClass(BuildingRegisterCollectCommand.class);
        verify(collection, times(5)).collect(command.capture());
        assertThat(command.getAllValues())
                .allSatisfy(value -> assertThat(value.maxRequests()).isOne());
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
        return command(10, 1);
    }

    private BuildingRegisterCampaignCommand command(int maxRequests, int parallelism) {
        return new BuildingRegisterCampaignCommand(
                COLLECTION_ID,
                REQUEST_ID,
                LocalDate.of(2026, 7, 20),
                BuildingRegisterCollectionMode.MISSING,
                BuildingRegisterCollectionStrategy.ADAPTIVE,
                maxRequests,
                null,
                100L,
                parallelism);
    }

    private BuildingRegisterCampaignTarget target(long id, String name) {
        return target(id, "1168010300101400001", name);
    }

    private BuildingRegisterCampaignTarget target(long id, String pnu, String name) {
        return new BuildingRegisterCampaignTarget(id, pnu, null, Set.of(name), Set.of(), Set.of());
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
