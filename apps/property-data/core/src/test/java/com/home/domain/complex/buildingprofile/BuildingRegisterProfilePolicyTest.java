package com.home.domain.complex.buildingprofile;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import com.home.domain.complex.buildingregister.BuildingRegisterHierarchyRecord;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildingRegisterProfilePolicyTest {
    private final BuildingProfileCollectionPolicy collectionPolicy = new BuildingProfileCollectionPolicy();
    private final BuildingProfileQualityPolicy qualityPolicy = new BuildingProfileQualityPolicy();

    @Test
    @DisplayName("profile 수집은 총괄 값이 완전해도 표제부를 항상 조회한다")
    void alwaysFetchesTitlesInProfileMode() {
        BuildingProfileCollectionDecision decision =
                collectionPolicy.decide(new BuildingProfileHierarchyFacts(1, 1, 1, true, false, false, false));

        assertThat(decision.fetchRecap()).isTrue();
        assertThat(decision.fetchTitles()).isTrue();
        assertThat(decision.fetchBasicOverview()).isFalse();
        assertThat(decision.basicOverviewReasons()).isEmpty();
    }

    @Test
    @DisplayName("shared 또는 계층 불명확 PNU에만 기본개요 사유를 남긴다")
    void fetchesBasicOverviewOnlyWithExplicitHierarchyReasons() {
        BuildingProfileCollectionDecision decision =
                collectionPolicy.decide(new BuildingProfileHierarchyFacts(2, 2, 3, false, true, true, true));

        assertThat(decision.fetchBasicOverview()).isTrue();
        assertThat(decision.basicOverviewReasons())
                .containsExactlyInAnyOrder(
                        BuildingProfileHierarchyReason.MULTIPLE_COMPLEXES,
                        BuildingProfileHierarchyReason.MULTIPLE_RECAP_ROOTS,
                        BuildingProfileHierarchyReason.MISSING_PARENT,
                        BuildingProfileHierarchyReason.PARENT_CONFLICT,
                        BuildingProfileHierarchyReason.AMBIGUOUS_GENERATION,
                        BuildingProfileHierarchyReason.UNASSIGNABLE_TITLE);
    }

    @Test
    @DisplayName("동일 총괄 중복은 복수 root로 세지 않고 세대 충돌만 불명확으로 판정한다")
    void derivesHierarchyFactsFromDistinctRootsAndGenerationEvidence() {
        var duplicate = new BuildingRegisterHierarchyRecord(
                BuildingRegisterEndpoint.RECAP_TITLE, "ROOT-1", null, 1, "1", "단지", null);
        var title = new BuildingRegisterHierarchyRecord(
                BuildingRegisterEndpoint.TITLE, "TITLE-1", "ROOT-1", 3, "1", "단지", "101동");

        BuildingProfileCollectionDecision duplicateDecision = collectionPolicy.decide(
                BuildingProfileHierarchyFacts.from(1, java.util.List.of(duplicate, duplicate, title)));
        BuildingProfileCollectionDecision generationConflict =
                collectionPolicy.decide(BuildingProfileHierarchyFacts.from(
                        1,
                        java.util.List.of(
                                duplicate,
                                new BuildingRegisterHierarchyRecord(
                                        BuildingRegisterEndpoint.RECAP_TITLE, "ROOT-1", null, 1, "0", "단지", null),
                                title)));

        assertThat(duplicateDecision.fetchBasicOverview()).isFalse();
        assertThat(generationConflict.basicOverviewReasons())
                .containsExactly(BuildingProfileHierarchyReason.AMBIGUOUS_GENERATION);
    }

    @Test
    @DisplayName("90% 기준은 저장이 아니라 운영 승격 추천에만 적용한다")
    void appliesCoverageThresholdOnlyToPromotionTier() {
        BuildingProfileQualityMetrics promotable = new BuildingProfileQualityMetrics(
                BuildingProfileScope.SITE, 0.95, 0.91, 0.97, 0.99, 0.0005, 0.004, true);
        BuildingProfileQualityMetrics retained =
                new BuildingProfileQualityMetrics(BuildingProfileScope.SITE, 0.99, 0.99, 0.89, 0.99, 0.0, 0.0, true);

        assertThat(qualityPolicy.classify(promotable)).isEqualTo(BuildingProfileQualityTier.PROMOTE_CANDIDATE);
        assertThat(qualityPolicy.classify(retained)).isEqualTo(BuildingProfileQualityTier.RETAIN_PROFILE);
        assertThat(BuildingProfileQualityTier.RETAIN_PROFILE.retainsTypedValue())
                .isTrue();
    }

    @Test
    @DisplayName("shared recap은 profile에 보존하지만 projectable하지 않다")
    void sharedRecapIsRetainedButNotProjectable() {
        assertThat(BuildingProfileAssignmentStatus.SHARED_SCOPE.retainsEvidence())
                .isTrue();
        assertThat(BuildingProfileAssignmentStatus.SHARED_SCOPE.projectable()).isFalse();
        assertThat(Set.of(BuildingProfileAssignmentStatus.values())).allSatisfy(status -> {
            assertThat(status.titleKo()).isNotBlank();
            assertThat(status.descriptionKo()).isNotBlank();
        });
    }
}
