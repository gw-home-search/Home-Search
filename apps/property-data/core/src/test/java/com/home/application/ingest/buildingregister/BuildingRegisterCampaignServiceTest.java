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

import com.home.domain.complex.buildingregister.BuildingRatioEvaluation;
import com.home.domain.complex.buildingregister.BuildingRatioResolutionStatus;
import com.home.domain.complex.buildingregister.BuildingRegisterCollectionMode;
import com.home.domain.complex.buildingregister.BuildingRegisterCollectionStrategy;
import com.home.domain.complex.buildingregister.BuildingRegisterComplexMatch;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BuildingRegisterCampaignServiceTest {
    private static final UUID COLLECTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174180");
    private static final UUID REQUEST_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174181");

    @Test
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
        return new BuildingRegisterRecordSnapshotCommand(
                0,
                "1168010300101400001",
                BuildingRegisterEndpoint.RECAP_TITLE,
                "ROOT-1",
                null,
                "1",
                "1",
                "1",
                null,
                "Shared",
                null,
                "02000",
                new BigDecimal("1000"),
                new BigDecimal("200"),
                new BigDecimal("900"),
                new BigDecimal("800"),
                new BigDecimal("20"),
                new BigDecimal("80"),
                2,
                0,
                700,
                LocalDate.of(2015, 1, 1),
                LocalDate.of(2026, 7, 20));
    }
}
