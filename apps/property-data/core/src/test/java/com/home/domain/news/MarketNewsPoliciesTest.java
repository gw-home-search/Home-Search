package com.home.domain.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketNewsPoliciesTest {

    private final MarketNewsRelationPolicy relationPolicy = new MarketNewsRelationPolicy();

    @Test
    @DisplayName("동명이고 짧은 현대 단지는 시군구와 법정동이 모두 없으면 직접 연결하지 않는다")
    void duplicatedShortNameRequiresSigunguAndDong() {
        NewsComplexEvidence modern = new NewsComplexEvidence(
                501L,
                "현대",
                null,
                List.of(),
                new NewsRegionEvidence("11", "서울특별시", "11680", "강남구", "11680105", "삼성동"),
                true);

        assertThat(relationPolicy.match("강남구 현대 아파트 재건축", "서울 주택 정책", List.of(modern)))
                .noneMatch(match -> match.relationType() == MarketNewsRelationType.DIRECT_COMPLEX);
        assertThat(relationPolicy.match("강남구 삼성동 현대 아파트 재건축", "서울 주택 정책", List.of(modern)))
                .singleElement()
                .satisfies(match -> {
                    assertThat(match.relationType()).isEqualTo(MarketNewsRelationType.DIRECT_COMPLEX);
                    assertThat(match.complexId()).isEqualTo(501L);
                });
    }

    @Test
    @DisplayName("query 출처 없이도 시군구+동 또는 시도+시군구 본문 근거만 지역 relation을 만든다")
    void regionRelationsRequireParentTokensInArticleText() {
        NewsComplexEvidence complex = new NewsComplexEvidence(
                501L,
                "래미안테스트",
                null,
                List.of(),
                new NewsRegionEvidence("11", "서울특별시", "11680", "강남구", "11680105", "삼성동"),
                false);

        assertThat(relationPolicy.match("삼성동 개발", "아파트 공급", List.of(complex))).isEmpty();
        assertThat(relationPolicy.match("강남구 삼성동 개발", "아파트 공급", List.of(complex)))
                .extracting(MarketNewsRelationMatch::relationType)
                .containsExactly(MarketNewsRelationType.SAME_DONG);
        assertThat(relationPolicy.match("서울특별시 강남구 개발", "아파트 공급", List.of(complex)))
                .extracting(MarketNewsRelationMatch::relationType)
                .containsExactly(MarketNewsRelationType.SAME_SIGUNGU);
        assertThat(relationPolicy.match("서울특별시 강남구난방 개선", "아파트 공급", List.of(complex)))
                .isEmpty();
    }

    @Test
    @DisplayName("시도 corpus index는 반복 기사에서도 기존 직접·지역 relation 결과를 유지한다")
    void indexedCorpusKeepsRelationResults() {
        NewsComplexEvidence complex = new NewsComplexEvidence(
                501L,
                "래미안테스트",
                "래미안 테스트",
                List.of("테스트래미안"),
                new NewsRegionEvidence("11", "서울특별시", "11680", "강남구", "11680105", "삼성동"),
                false);
        var index = relationPolicy.index(List.of(complex));

        assertThat(relationPolicy.match("강남구 삼성동 래미안테스트 재건축", "아파트 정비사업", index))
                .isEqualTo(relationPolicy.match("강남구 삼성동 래미안테스트 재건축", "아파트 정비사업", List.of(complex)));
        assertThat(relationPolicy.match("서울특별시 강남구 공급", "주택 분양", index))
                .isEqualTo(relationPolicy.match("서울특별시 강남구 공급", "주택 분양", List.of(complex)));
    }

    @Test
    @DisplayName("시군구 지명과 동일한 단지명은 견본주택 주소가 있어도 직접 단지 relation을 만들지 않는다")
    void geographicComplexNameDoesNotCreateDirectRelationFromModelHouseAddress() {
        NewsComplexEvidence complex = new NewsComplexEvidence(
                32852L,
                "김포",
                "김포",
                List.of(),
                new NewsRegionEvidence("41", "경기도", "41570", "김포시", "41570106", "사우동"),
                false);

        assertThat(relationPolicy.match(
                        "호반써밋 풍무Ⅲ 1순위 청약 마감", "김포 풍무역세권 아파트의 견본주택은 경기도 김포시 사우동 547-8번지에 위치한다", List.of(complex)))
                .noneMatch(match -> match.relationType() == MarketNewsRelationType.DIRECT_COMPLEX);
    }

    @Test
    @DisplayName("카테고리 동점은 고정 enum 순서로 결정하고 부동산 allowlist 없는 기사는 제외한다")
    void categoryUsesDeterministicOrderAndAllowlist() {
        MarketNewsClassificationPolicy policy = new MarketNewsClassificationPolicy();

        assertThat(policy.classify("아파트 정책과 대출", "정책 대출")).contains(MarketNewsCategory.POLICY);
        assertThat(policy.classify("아파트 화재 현장", "주민 대피")).isEmpty();
        assertThat(policy.classify("야구 경기 결과", "선수 인터뷰")).isEmpty();
    }

    @Test
    @DisplayName("해충 사건과 기업 실적처럼 부동산 단어가 부수적으로 등장한 기사는 제외한다")
    void excludesIncidentalRealEstateMentions() {
        MarketNewsClassificationPolicy policy = new MarketNewsClassificationPolicy();

        assertThat(policy.classify("온난화가 부른 바퀴벌레 습격 서울 민원 증가", "재개발 아파트 주변 방역 대책"))
                .isEmpty();
        assertThat(policy.classify("특판 감소 B2B 축소 한샘 실적 발표", "소비자 리모델링 매출 확대")).isEmpty();
        assertThat(policy.classify("아파트 리모델링 사업성 높인다", "정비사업 용적률과 분담금 개선")).contains(MarketNewsCategory.REDEVELOPMENT);
    }

    @Test
    @DisplayName("저장 상태 enum은 한국어 근거와 허용된 전이만 제공한다")
    void storedStatesOwnMetadataAndTransitions() {
        assertThat(MarketNewsSnapshotState.values()).allSatisfy(state -> {
            assertThat(state.titleKo()).isNotBlank();
            assertThat(state.descriptionKo()).isNotBlank();
        });
        assertThat(MarketNewsExecutionState.values()).allSatisfy(state -> {
            assertThat(state.titleKo()).isNotBlank();
            assertThat(state.descriptionKo()).isNotBlank();
        });
        assertThat(MarketNewsWorkUnitState.values()).allSatisfy(state -> {
            assertThat(state.titleKo()).isNotBlank();
            assertThat(state.descriptionKo()).isNotBlank();
        });
        assertThat(MarketNewsSnapshotState.BUILDING.canTransitionTo(MarketNewsSnapshotState.PUBLISHED))
                .isTrue();
        assertThat(MarketNewsSnapshotState.PUBLISHED.canTransitionTo(MarketNewsSnapshotState.WITHDRAWN))
                .isTrue();
        assertThat(MarketNewsSnapshotState.WITHDRAWN.canTransitionTo(MarketNewsSnapshotState.PUBLISHED))
                .isFalse();
        assertThat(MarketNewsExecutionState.PLANNED.canTransitionTo(MarketNewsExecutionState.RUNNING))
                .isTrue();
        assertThat(MarketNewsExecutionState.RUNNING.canTransitionTo(MarketNewsExecutionState.PARTIAL))
                .isTrue();
        assertThat(MarketNewsExecutionState.COMPLETED.canTransitionTo(MarketNewsExecutionState.RUNNING))
                .isFalse();
        assertThat(MarketNewsWorkUnitState.RUNNING.canTransitionTo(MarketNewsWorkUnitState.SKIPPED_BUDGET))
                .isTrue();
        assertThat(MarketNewsWorkUnitState.COMPLETED.isSuccessful()).isTrue();
        assertThat(MarketNewsWorkUnitState.FAILED.isSuccessful()).isFalse();
        assertThat(MarketNewsWithdrawalReason.values()).allSatisfy(reason -> {
            assertThat(reason.titleKo()).isNotBlank();
            assertThat(reason.descriptionKo()).isNotBlank();
        });
    }

    @Test
    @DisplayName("뉴스 분류·관계·scope enum은 한국어 근거와 저장 구분을 유지한다")
    void classificationEnumsOwnKoreanMetadata() {
        assertThat(MarketNewsCategory.values()).allSatisfy(value -> {
            assertThat(value.titleKo()).isNotBlank();
            assertThat(value.descriptionKo()).isNotBlank();
        });
        assertThat(MarketNewsCategory.ALL.isStoredCategory()).isFalse();
        assertThat(MarketNewsCategory.POLICY.isStoredCategory()).isTrue();
        assertThat(MarketNewsDataStatus.values()).allSatisfy(value -> {
            assertThat(value.titleKo()).isNotBlank();
            assertThat(value.descriptionKo()).isNotBlank();
        });
        assertThat(MarketNewsRelationType.values()).allSatisfy(value -> {
            assertThat(value.titleKo()).isNotBlank();
            assertThat(value.descriptionKo()).isNotBlank();
        });
        assertThat(MarketNewsScopeType.values()).allSatisfy(value -> {
            assertThat(value.titleKo()).isNotBlank();
            assertThat(value.descriptionKo()).isNotBlank();
        });
        assertThat(MarketNewsWorkUnitKind.values()).allSatisfy(value -> {
            assertThat(value.titleKo()).isNotBlank();
            assertThat(value.descriptionKo()).isNotBlank();
        });
        assertThat(NewsRejectionReason.values()).allSatisfy(value -> {
            assertThat(value.titleKo()).isNotBlank();
            assertThat(value.descriptionKo()).isNotBlank();
        });
    }
}
