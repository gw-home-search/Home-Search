package com.home.application.ingest.buildingregister;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.domain.complex.buildingregister.BuildingRatioField;
import com.home.domain.complex.buildingregister.BuildingRatioProjectionOutcome;
import com.home.domain.complex.buildingregister.BuildingRegisterCollectionMode;
import com.home.domain.complex.buildingregister.BuildingRegisterCollectionStrategy;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildingRegisterCommandValidationTest {
    private static final UUID COLLECTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174190");
    private static final UUID REQUEST_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174191");
    private static final String PNU = "1168010300101400001";
    private static final String SHA256 = "a".repeat(64);

    @Test
    @DisplayName("건축물대장 명령 검증 규칙을 확인한다")
    void rejectsUnsafeProjectionRanges() {
        assertThatThrownBy(() -> new BuildingRatioProjectCommand(null, REQUEST_ID, 1, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BuildingRatioProjectCommand(COLLECTION_ID, null, 1, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BuildingRatioProjectCommand(COLLECTION_ID, REQUEST_ID, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BuildingRatioProjectCommand(COLLECTION_ID, REQUEST_ID, 1, 0L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BuildingRatioProjectCommand(COLLECTION_ID, REQUEST_ID, 1, null, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BuildingRatioProjectCommand(COLLECTION_ID, REQUEST_ID, 1, 2L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BuildingRatioProjectionTarget(0, BuildingRatioField.BUILDING_COVERAGE_RATIO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BuildingRatioProjectionTarget(1, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BuildingRatioProjectionTarget(1, BuildingRatioField.BUILDING_COVERAGE_RATIO, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("건축물대장 명령 검증 규칙을 확인한다")
    void validatesRawReceiptIntegrityAndSize() {
        assertThatThrownBy(() -> receipt(0, 1, 1, "{}", 2, SHA256, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BuildingRegisterRawPageReceiptCommand(1, null, 1, 1, "{}", SHA256, 2, 200, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> receipt(1, 0, 1, "{}", 2, SHA256, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receipt(1, 1, 0, "{}", 2, SHA256, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receipt(1, 1, 1, "{}", 2, "BAD", 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receipt(1, 1, 1, null, -1, SHA256, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receipt(1, 1, 1, "가", 1, SHA256, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("byteCount");
        assertThatThrownBy(() -> receipt(1, 1, 1, "a".repeat(2 * 1024 * 1024 + 1), 2 * 1024 * 1024 + 1, SHA256, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 MiB");
        assertThatThrownBy(() -> receipt(1, 1, 1, "{}", 2, SHA256, 99)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receipt(1, 1, 1, "{}", 2, SHA256, 600)).isInstanceOf(IllegalArgumentException.class);

        assertThat(receipt(1, 1, 1, null, 2_097_153, SHA256, null).responseBody())
                .isNull();
    }

    @Test
    @DisplayName("건축물대장 명령 검증 규칙을 확인한다")
    void validatesProviderResponseCoordinatesAndFailureClassification() {
        assertThatThrownBy(() -> response(null, PNU, 1, 100, 200, "{}", 2, SHA256, false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> response(BuildingRegisterEndpoint.TITLE, "bad", 1, 100, 200, "{}", 2, SHA256, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> response(BuildingRegisterEndpoint.TITLE, PNU, 0, 100, 200, "{}", 2, SHA256, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> response(BuildingRegisterEndpoint.TITLE, PNU, 1, 0, 200, "{}", 2, SHA256, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> response(BuildingRegisterEndpoint.TITLE, PNU, 1, 100, 99, "{}", 2, SHA256, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> response(BuildingRegisterEndpoint.TITLE, PNU, 1, 100, 200, "{}", -1, SHA256, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> response(BuildingRegisterEndpoint.TITLE, PNU, 1, 100, 200, "{}", 2, "BAD", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> response(BuildingRegisterEndpoint.TITLE, PNU, 1, 100, 200, "{}", 2, SHA256, true))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(response(BuildingRegisterEndpoint.TITLE, PNU, 1, 100, 204, null, 0, SHA256, false)
                        .httpSuccessful())
                .isTrue();
        assertThat(response(BuildingRegisterEndpoint.TITLE, PNU, 1, 100, 401, null, 0, SHA256, false)
                        .authenticationOrQuotaFailure())
                .isTrue();
        assertThat(response(BuildingRegisterEndpoint.TITLE, PNU, 1, 100, 403, null, 0, SHA256, false)
                        .authenticationOrQuotaFailure())
                .isTrue();
        assertThat(response(BuildingRegisterEndpoint.TITLE, PNU, 1, 100, 429, null, 0, SHA256, false)
                        .authenticationOrQuotaFailure())
                .isTrue();
        assertThat(response(BuildingRegisterEndpoint.TITLE, PNU, 1, 100, 500, null, 0, SHA256, false)
                        .authenticationOrQuotaFailure())
                .isFalse();
    }

    @Test
    @DisplayName("건축물대장 명령 검증 규칙을 확인한다")
    void validatesCampaignAndPageCommands() {
        assertThatThrownBy(() -> campaign(null, REQUEST_ID, LocalDate.now(), 1, null, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> campaign(COLLECTION_ID, null, LocalDate.now(), 1, null, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> campaign(COLLECTION_ID, REQUEST_ID, null, 1, null, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BuildingRegisterCampaignCommand(
                        COLLECTION_ID,
                        REQUEST_ID,
                        LocalDate.now(),
                        null,
                        BuildingRegisterCollectionStrategy.ADAPTIVE,
                        1,
                        null,
                        1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BuildingRegisterCampaignCommand(
                        COLLECTION_ID,
                        REQUEST_ID,
                        LocalDate.now(),
                        BuildingRegisterCollectionMode.MISSING,
                        null,
                        1,
                        null,
                        1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> campaign(COLLECTION_ID, REQUEST_ID, LocalDate.now(), 0, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> campaign(COLLECTION_ID, REQUEST_ID, LocalDate.now(), 1, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> campaign(COLLECTION_ID, REQUEST_ID, LocalDate.now(), 1, 0L, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> campaign(COLLECTION_ID, REQUEST_ID, LocalDate.now(), 1, 2L, 1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new BuildingRegisterPageRequest(null, PNU, 1, 100))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BuildingRegisterPageRequest(BuildingRegisterEndpoint.TITLE, "bad", 1, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BuildingRegisterPageRequest(BuildingRegisterEndpoint.TITLE, PNU, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BuildingRegisterPageRequest(BuildingRegisterEndpoint.TITLE, PNU, 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(List.of(10, 25, 50, 100))
                .allSatisfy(
                        size -> assertThat(new BuildingRegisterPageRequest(BuildingRegisterEndpoint.TITLE, PNU, 1, size)
                                        .pageSize())
                                .isEqualTo(size));
    }

    @Test
    @DisplayName("건축물대장 명령 검증 규칙을 확인한다")
    void copiesEvidenceCollectionsAtApplicationBoundary() {
        List<BuildingRegisterRecordSnapshotCommand> records = new ArrayList<>();
        Set<BuildingRatioField> fields = new HashSet<>();
        var result = new BuildingRegisterCollectionResult(null, 0, records, null, null, fields);
        records.add(null);
        fields.add(BuildingRatioField.FLOOR_AREA_RATIO);
        assertThat(result.recapRecords()).isEmpty();
        assertThat(result.titleRecords()).isEmpty();
        assertThat(result.basicOverviewRecords()).isEmpty();
        assertThat(result.fallbackFields()).isEmpty();

        var target = new BuildingRegisterCampaignTarget(1, PNU, null, null, null, null);
        assertThat(target.matchTarget().names()).isEmpty();

        Map<BuildingRatioProjectionOutcome, Integer> outcomes = new EnumMap<>(BuildingRatioProjectionOutcome.class);
        var summary = new BuildingRatioProjectionSummary(0, outcomes);
        outcomes.put(BuildingRatioProjectionOutcome.APPLIED, 1);
        assertThat(summary.outcomes()).isEmpty();
        assertThat(new BuildingRatioProjectionSummary(0, null).outcomes()).isEmpty();

        Map<BuildingRatioField, Long> selected = new EnumMap<>(BuildingRatioField.class);
        var evaluation = new BuildingRatioRecordedEvaluation(selected);
        selected.put(BuildingRatioField.BUILDING_COVERAGE_RATIO, 1L);
        assertThat(evaluation.selectedCandidateIds()).isEmpty();
        assertThat(new BuildingRatioRecordedEvaluation(null).selectedCandidateIds())
                .isEmpty();

        assertThat(new ParsedBuildingRegisterPage("00", null, 0, null).records())
                .isEmpty();
        assertThat(new BuildingRegisterCompletedPage(0, null).records()).isEmpty();
        assertThatThrownBy(() -> new ParsedBuildingRegisterPage("00", null, -1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BuildingRegisterCompletedPage(-1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BuildingRegisterEndpointSnapshot(0, BuildingRegisterEndpoint.TITLE, 100, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private BuildingRegisterCampaignCommand campaign(
            UUID collectionId,
            UUID requestId,
            LocalDate runDate,
            int maxRequests,
            Long fromComplexId,
            long toComplexId) {
        return new BuildingRegisterCampaignCommand(
                collectionId,
                requestId,
                runDate,
                BuildingRegisterCollectionMode.MISSING,
                BuildingRegisterCollectionStrategy.ADAPTIVE,
                maxRequests,
                fromComplexId,
                toComplexId);
    }

    private BuildingRegisterRawPageReceiptCommand receipt(
            long snapshotId, int pageNo, int attemptNo, String body, int byteCount, String sha256, Integer status) {
        return new BuildingRegisterRawPageReceiptCommand(
                snapshotId, REQUEST_ID, pageNo, attemptNo, body, sha256, byteCount, status, null);
    }

    private BuildingRegisterPageResponse response(
            BuildingRegisterEndpoint endpoint,
            String pnu,
            int pageNo,
            int pageSize,
            int status,
            String body,
            long byteCount,
            String sha256,
            boolean oversized) {
        return new BuildingRegisterPageResponse(
                endpoint, pnu, pageNo, pageSize, status, body, byteCount, sha256, oversized);
    }
}
