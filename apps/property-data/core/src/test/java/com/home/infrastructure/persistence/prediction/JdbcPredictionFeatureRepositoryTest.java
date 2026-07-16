package com.home.infrastructure.persistence.prediction;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.prediction.PredictionFeatureAssembler;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;
import java.time.YearMonth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcPredictionFeatureRepositoryTest extends JdbcPostgresTestSupport {

    @Test
    @DisplayName("예측 feature는 최신 정상 거래를 basis로 선택하고 PNU embedding을 파생한다")
    void predictionFeatureUsesLatestTradeBasisAndPnuEmbedding() {
        seedPropertyExplorationData();
        seedExtraTradesForExactAreaFilter();
        JdbcPredictionFeatureRepository repository = new JdbcPredictionFeatureRepository(jdbcClient);

        var basis = repository.findBasis(501L).orElseThrow();
        assertThat(repository.readSnapshot(basis, YearMonth.of(2026, 6)))
                .map(snapshot -> new PredictionFeatureAssembler().assemble(basis, snapshot))
                .hasValueSatisfying(feature -> {
                    assertThat(feature.complexId()).isEqualTo(501L);
                    assertThat(feature.basisTradeId()).isEqualTo(9002L);
                    assertThat(feature.targetAreaM2()).isEqualByComparingTo("84.93");
                    assertThat(feature.targetFloor()).isEqualTo(15);
                    assertThat(feature.embeddingFeatures())
                            .containsEntry("legal_dong_code", "1168010300")
                            .containsEntry("sgg_code", "11680");
                    assertThat(number(feature.numericFeatures().get("area_m2"))).isEqualTo(84.93);
                    assertThat(number(feature.numericFeatures().get("floor"))).isEqualTo(15.0);
                    assertThat(number(feature.numericFeatures().get("complex_prev_missing")))
                            .isZero();
                    assertThat(number(feature.numericFeatures().get("exact_prev1_missing")))
                            .isZero();
                    assertThat(number(feature.numericFeatures().get("exact_prev3_area_abs_diff")))
                            .isCloseTo(0.37, org.assertj.core.data.Offset.offset(0.001));
                });
    }

    @Test
    @DisplayName("최근 정상 거래가 없으면 예측 feature를 만들지 않는다")
    void missingRecentTradeReturnsEmptyFeature() {
        seedPropertyExplorationData();
        JdbcPredictionFeatureRepository repository = new JdbcPredictionFeatureRepository(jdbcClient);

        assertThat(repository.findBasis(999L)).isEmpty();
    }

    private void seedExtraTradesForExactAreaFilter() {
        jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    id, source, source_key, lawd_cd, deal_ymd, page_no, payload, payload_hash, status, processed_at
			)
			VALUES
			    (90003, 'RTMS', 'prediction-excluded-area', '11680', '202511', 1, '{}', 'hash-prediction-excluded', 'NORMALIZED', now()),
			    (90004, 'RTMS', 'prediction-included-area', '11680', '202510', 1, '{}', 'hash-prediction-included', 'NORMALIZED', now())
			""").update();
        jdbcClient.sql("""
			INSERT INTO trade (
			    id, complex_id, deal_date, deal_amount, floor, excl_area, apt_dong, source, source_key, complex_pk, apt_seq, raw_ingest_id
			)
			VALUES
			    (9003, 501, DATE '2025-11-20', 121000, 10, 85.50, '101', 'RTMS', 'prediction-excluded-area', 'COMPLEX-PK-501', 'APT-501', 90003),
			    (9004, 501, DATE '2025-10-20', 120000, 9, 85.30, '101', 'RTMS', 'prediction-included-area', 'COMPLEX-PK-501', 'APT-501', 90004)
			""").update();
    }

    private static double number(Object value) {
        assertThat(value).isInstanceOf(Number.class);
        return ((Number) value).doubleValue();
    }
}
