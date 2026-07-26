package com.home.application.news.collection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketNewsQueryPolicyRegistryTest {

    private final MarketNewsQueryPolicyRegistry registry = new MarketNewsQueryPolicyRegistry();

    @Test
    @DisplayName("NEWS_V5는 정밀도 규칙을 유지하면서 시도 부족 표본용 두 번째 query를 제공한다")
    void providesVersionedSupplementalSidoQueries() {
        assertThat(MarketNewsQueryPolicyRegistry.POLICY_VERSION).isEqualTo("NEWS_V5");
        assertThat(registry.sido("제주특별자치도")).containsExactly("제주특별자치도 아파트 부동산", "제주특별자치도 주택 분양");
    }

    @Test
    @DisplayName("NEWS_V5는 일반 주요 단지 한 번, challenge 주요 단지만 두 번 조회한다")
    void boundsSupplementalMajorComplexQueriesToChallenges() {
        assertThat(registry.majorComplex("제주시", "연동", "세경아파트", false)).containsExactly("제주시 연동 세경아파트 아파트");
        assertThat(registry.majorComplex("제주시", "연동", "세경", true)).containsExactly("제주시 연동 세경 아파트", "제주시 연동 세경");
    }
}
