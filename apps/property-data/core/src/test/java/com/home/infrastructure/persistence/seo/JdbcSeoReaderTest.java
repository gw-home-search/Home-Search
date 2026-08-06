package com.home.infrastructure.persistence.seo;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.seo.SeoIndexMode;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcSeoReaderTest extends JdbcPostgresTestSupport {

    @Test
    @DisplayName("색인 가능한 단지와 콘텐츠 부족 단지를 구분하고 PILOT 카탈로그를 만든다")
    void separatesIndexableAndContentPoorComplexesAndBuildsPilotCatalog() {
        seedComplex();
        jdbcClient.sql("""
                INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
                VALUES (1002, 1, '1168010300101400002', 'Content poor address', 37.5124, 127.0457)
                """).update();
        jdbcClient.sql("""
                INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name)
                VALUES (502, 1002, 'COMPLEX-PK-502', 'APT-502', 'Content Poor Apartment')
                """).update();
        JdbcSeoReader reader = new JdbcSeoReader(jdbcClient);

        refreshPilotCatalog();

        assertThat(reader.findComplex(501L)).get().satisfies(result -> {
            assertThat(result.indexable()).isTrue();
            assertThat(result.breadcrumbs()).extracting("regionId").containsExactly(1L);
        });
        assertThat(reader.findComplex(502L)).get().extracting("indexable").isEqualTo(false);
        assertThat(reader.findComplex(999L)).isEmpty();
        assertThat(reader.findComplexCatalog(SeoIndexMode.PILOT, 0, 1000))
                .extracting("complexId")
                .containsExactly(501L);
        assertThat(reader.findRegionCatalog(SeoIndexMode.PILOT))
                .extracting("regionId")
                .containsExactly(1L);
        assertThat(reader.findRegion(1L, SeoIndexMode.PILOT)).get().satisfies(result -> {
            assertThat(result.indexable()).isTrue();
            assertThat(result.indexableComplexCount()).isEqualTo(1L);
        });
    }

    @Test
    @DisplayName("재개발 관계의 신뢰도 높은 이전 단지를 페이지와 지역 집계에서 제외한다")
    void excludesHighConfidenceRedevelopmentPredecessorFromPagesAndRegionCounts() {
        seedComplex();
        jdbcClient
                .sql("UPDATE complex SET use_date=DATE '2010-01-01' WHERE id=501")
                .update();
        jdbcClient.sql("""
                INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt, use_date)
                VALUES (503, 1001, 'COMPLEX-PK-503', 'APT-503', 'Replacement Apartment', 900, DATE '2024-01-01')
                """).update();
        jdbcClient.sql("""
                INSERT INTO complex_coordinate_case (
                    parcel_id, pnu, status, relation_type, relation_confidence, reason
                ) VALUES (
                    1001, '1168010300101400001', 'RESOLVED', 'REDEVELOPED', 'HIGH', 'test replacement'
                )
                """).update();
        JdbcSeoReader reader = new JdbcSeoReader(jdbcClient);

        refreshPilotCatalog();

        assertThat(reader.findComplex(501L)).isEmpty();
        assertThat(reader.findComplex(503L)).isPresent();
        assertThat(reader.findComplexCatalog(SeoIndexMode.PILOT, 0, 1000))
                .extracting("complexId")
                .containsExactly(503L);
        assertThat(reader.findRegion(1L, SeoIndexMode.PILOT)).get().satisfies(result -> {
            assertThat(result.indexableComplexCount()).isEqualTo(1L);
            assertThat(result.representativeComplexes()).extracting("complexId").containsExactly(503L);
        });
    }

    @Test
    @DisplayName("PILOT 카탈로그를 1,000개로 고정하고 선택 이후 keyset을 적용한다")
    void fixesPilotCatalogAtOneThousandAndAppliesKeysetAfterSelection() {
        seedComplex();
        jdbcClient.sql("""
                INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
                SELECT value + 10_000, 1, LPAD(value::text, 19, '0'), 'Address ' || value, 37.5, 127.0
                FROM generate_series(1_000, 2_000) value
                """).update();
        jdbcClient.sql("""
                INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt)
                SELECT value, value + 10_000, 'COMPLEX-PK-' || value, 'APT-' || value,
                       'Apartment ' || value, 100
                FROM generate_series(1_000, 2_000) value
                """).update();
        JdbcSeoReader reader = new JdbcSeoReader(jdbcClient);

        refreshPilotCatalog();

        assertThat(reader.findComplexCatalog(SeoIndexMode.PILOT, 0, 10_000))
                .hasSize(1_000)
                .first()
                .extracting("complexId")
                .isEqualTo(501L);
        assertThat(reader.findComplexCatalog(SeoIndexMode.PILOT, 1_997, 10_000))
                .extracting("complexId")
                .containsExactly(1_998L);
    }

    @Test
    @DisplayName("배포된 PILOT 카탈로그는 후속 단지 추가에도 고정된다")
    void keepsPilotSelectionFixedAfterTheCatalogIsPublished() {
        seedComplex();
        refreshPilotCatalog();
        jdbcClient.sql("""
                INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
                VALUES (1003, 1, '1168010300101400003', 'New address', 37.5124, 127.0457)
                """).update();
        jdbcClient.sql("""
                INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt)
                VALUES (503, 1003, 'COMPLEX-PK-503', 'APT-503', 'New Apartment', 100)
                """).update();

        JdbcSeoReader reader = new JdbcSeoReader(jdbcClient);

        assertThat(reader.findComplexCatalog(SeoIndexMode.PILOT, 0, 10_000))
                .extracting("complexId")
                .containsExactly(501L);

        assertThat(reader.findRegion(1L, SeoIndexMode.PILOT)).get().satisfies(result -> {
            assertThat(result.indexableComplexCount()).isEqualTo(1L);
            assertThat(result.representativeComplexes()).extracting("complexId").containsExactly(501L);
        });
    }

    private void refreshPilotCatalog() {
        jdbcClient.sql("SELECT refresh_seo_pilot_complex_catalog()")
                .query(Integer.class)
                .single();
    }
}
