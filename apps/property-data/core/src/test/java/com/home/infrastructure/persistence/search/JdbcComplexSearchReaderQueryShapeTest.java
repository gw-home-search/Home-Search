package com.home.infrastructure.persistence.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcComplexSearchReaderQueryShapeTest {

    @Test
    @DisplayName("긴 단일 검색어의 contains 단계는 trigram index가 가능한 direct parameter를 사용한다")
    void longSingleTermContainsUsesDirectBoundPatterns() {
        PropertySearchTerms terms = PropertySearchTerms.from("마포래미안푸르지오");

        String candidates = JdbcComplexSearchReader.containsCandidatesFor(terms);

        assertThat(candidates)
                .contains("LIKE :rawPattern")
                .contains("LIKE :normalizedPattern")
                .doesNotContain("query_tokens");
        assertThat(terms.rawContainsPattern()).isEqualTo("%마포래미안푸르지오%");
        assertThat(terms.normalizedContainsPattern()).isEqualTo("%마포래미안푸르지오%");
    }

    @Test
    @DisplayName("복합 검색어 contains 단계는 모든 검색어를 AND로 유지한다")
    void multipleTermsKeepAndMatchingQuery() {
        PropertySearchTerms terms = PropertySearchTerms.from("응봉동 대림");

        assertThat(JdbcComplexSearchReader.containsCandidatesFor(terms))
                .contains("query_tokens")
                .contains("HAVING count(DISTINCT hit.token_no)");
    }
}
