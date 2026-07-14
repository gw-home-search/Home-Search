package com.home.infrastructure.persistence.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.domain.complex.buildingmetadata.ComplexNameNormalizer;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ComplexNameNormalizerParityTest extends JdbcPostgresTestSupport {

    @ParameterizedTest
    @ValueSource(strings = {" 래미안! 1차:아파트 ", "래미안（1차）-아파트", "THE  HILL_A.P.T", "e편한세상/서울[1단지]"})
    @DisplayName("Java complex name normalizer는 V8 search projection 정규화와 같다")
    void javaNormalizerMatchesV8SearchProjection(String sourceName) {
        String databaseNormalized = jdbcClient
                .sql("SELECT hs_normalize_complex_search_name(:sourceName)")
                .param("sourceName", sourceName)
                .query(String.class)
                .single();

        assertThat(ComplexNameNormalizer.normalize(sourceName)).isEqualTo(databaseNormalized);
    }
}
