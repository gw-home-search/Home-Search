package com.home.infrastructure.persistence.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.home.application.map.ComplexMarkerQuery;
import com.home.application.map.ComplexMarkerResult;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcMapMarkerRepositoryTest extends JdbcPostgresTestSupport {

    @Test
    @DisplayName("bounds query는 latest trade amount와 unit sum이 있는 parcel complex marker를 반환한다")
    void boundsQueryReturnsComplexMarkers() {
        seedMapData();
        JdbcMapMarkerRepository repository = mapMarkerRepository();

        var markers = repository.findComplexMarkers(request(null, null));

        assertThat(markers)
                .extracting(
                        ComplexMarkerResult::parcelId,
                        ComplexMarkerResult::complexId,
                        ComplexMarkerResult::name,
                        ComplexMarkerResult::lat,
                        ComplexMarkerResult::lng,
                        ComplexMarkerResult::latestDealAmount,
                        ComplexMarkerResult::unitCntSum)
                .containsExactly(tuple(1001L, null, "Sample Apartment B", 37.5123, 127.0456, 125000L, 860L));
    }

    @Test
    @DisplayName("price eok filter는 10,000 KRW trade amount unit으로 변환된다")
    void priceEokFiltersUseTradeAmountUnits() {
        seedMapData();
        JdbcMapMarkerRepository repository = mapMarkerRepository();

        assertThat(repository.findComplexMarkers(request(12.0, 13.0))).hasSize(1);
        assertThat(repository.findComplexMarkers(request(13.0, null))).isEmpty();
    }

    @Test
    @DisplayName("unit filter는 반환 marker의 세대수 합계를 기준으로 적용된다")
    void unitFilterUsesReturnedMarkerUnitSum() {
        seedMapData();
        JdbcMapMarkerRepository repository = mapMarkerRepository();

        assertThat(repository.findComplexMarkers(unitRequest(800L, 900L)))
                .extracting(
                        ComplexMarkerResult::parcelId, ComplexMarkerResult::complexId, ComplexMarkerResult::unitCntSum)
                .containsExactly(tuple(1001L, null, 860L));
        assertThat(repository.findComplexMarkers(unitRequest(861L, null))).isEmpty();
    }

    @Test
    @DisplayName("neutral unit·age filter variant는 trade-first variant와 같은 marker를 반환한다")
    void neutralMarkerShapeFiltersMatchTradeFirstVariant() {
        seedMapData();
        jdbcClient
                .sql("UPDATE complex SET use_date = DATE '2010-01-01' WHERE parcel_id = 1001")
                .update();
        JdbcMapMarkerRepository repository = mapMarkerRepository();

        var tradeFirstMarkers = repository.findComplexMarkers(request(null, null));
        var markerShapeFilterMarkers = repository.findComplexMarkers(shapeFilterRequest());

        assertThat(markerShapeFilterMarkers).containsExactlyElementsOf(tradeFirstMarkers);
    }

    @Test
    @DisplayName("가격·면적·연식·세대수 조합은 marker identity와 source name을 유지한다")
    void combinedFiltersKeepMarkerIdentityAndSourceName() {
        seedMapData();
        jdbcClient
                .sql("UPDATE complex SET use_date = DATE '2010-01-01' WHERE parcel_id = 1001")
                .update();
        JdbcMapMarkerRepository repository = mapMarkerRepository();

        assertThat(repository.findComplexMarkers(combinedFilterRequest()))
                .extracting(
                        ComplexMarkerResult::parcelId,
                        ComplexMarkerResult::complexId,
                        ComplexMarkerResult::name,
                        ComplexMarkerResult::latestDealAmount,
                        ComplexMarkerResult::unitCntSum)
                .containsExactly(tuple(1001L, null, "Sample Apartment B", 125000L, 860L));
    }

    @Test
    @DisplayName("ratio filter는 direct complex 값을 우선하고 없을 때 PNU fallback을 사용한다")
    void ratioFilterPrefersDirectValueAndUsesPublishedFallback() {
        seedMapData();
        seedPublishedRatioFallback(501L, new BigDecimal("70.0"), new BigDecimal("240.0"));
        jdbcClient
                .sql("UPDATE complex SET bc_rat=40.0, vl_rat=120.0 WHERE id=501")
                .update();
        JdbcMapMarkerRepository repository = mapMarkerRepository();

        assertThat(repository.findComplexMarkers(ratioRequest("60.0", "80.0", "200.0", "260.0")))
                .isEmpty();

        jdbcClient
                .sql("UPDATE complex SET bc_rat=NULL, vl_rat=NULL WHERE id=501")
                .update();
        repository = mapMarkerRepository();
        assertThat(repository.findComplexMarkers(ratioRequest("60.0", "80.0", "200.0", "260.0")))
                .hasSize(1);
    }

    @Test
    @DisplayName("map query EXPLAIN은 PostGIS와 latest-trade index 경로를 유지한다")
    void explainUsesSpatialAndLatestTradeIndexes() {
        seedMapData();
        jdbcClient.sql("""
			UPDATE parcel
			SET geom = ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(longitude, latitude), 4326), 0.0001))
			""").update();
        seedPlannerScaleOutsideBounds();

        List<String> plan = transactionTemplate.execute(status -> {
            jdbcClient.sql("SET LOCAL enable_seqscan = off").update();
            return ComplexMarkerJdbcParameters.from(request(null, null))
                    .bindTradeFirst(jdbcClient.sql("EXPLAIN (COSTS OFF) " + ComplexMarkerSql.tradeFirst()))
                    .query(String.class)
                    .list();
        });

        assertThat(String.join("\n", plan))
                .contains("ix_parcel_geom")
                .contains("complex_id_deal_date_id_deal_amount_excl_area_idx");
    }

    private void seedPlannerScaleOutsideBounds() {
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude, geom)
			SELECT
			    10000 + fixture_id,
			    1,
			    (2000000000000000000 + fixture_id)::text,
			    'Planner scale fixture',
			    35.0,
			    125.0,
			    ST_Multi(ST_Buffer(ST_SetSRID(ST_MakePoint(125.0, 35.0), 4326), 0.0001))
			FROM generate_series(1, 2000) AS fixture_id
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt)
			SELECT
			    20000 + fixture_id,
			    10000 + fixture_id,
			    'PLANNER-COMPLEX-' || fixture_id,
			    'PLANNER-APT-' || fixture_id,
			    'Planner scale fixture',
			    1
			FROM generate_series(1, 2000) AS fixture_id
			""").update();
        jdbcClient.sql("ANALYZE parcel").update();
        jdbcClient.sql("ANALYZE complex").update();
    }

    @Test
    @DisplayName("bounds query는 complex 세대수 metadata가 없으면 marker를 반환하지 않는다")
    void boundsQueryExcludesMarkerWhenComplexMetadataIsMissing() {
        seedMissingUnitCountMapData();
        JdbcMapMarkerRepository repository = mapMarkerRepository();

        assertThat(repository.findComplexMarkers(request(null, null))).isEmpty();
    }

    @Test
    @DisplayName("좌표가 없는 coordinate-pending parcel은 거래가 있어도 marker로 반환하지 않는다")
    void coordinatePendingParcelWithTradeIsExcludedFromMarkers() {
        seedCoordinatePendingMapData();
        JdbcMapMarkerRepository repository = mapMarkerRepository();

        assertThat(repository.findComplexMarkers(request(null, null))).isEmpty();
    }

    @Test
    @DisplayName("bounds query는 canceled trade를 latest amount 후보에서 제외한다")
    void boundsQueryExcludesCanceledTradeFromLatestAmount() {
        seedMapData();
        jdbcClient.sql("""
			UPDATE trade
			SET deleted_at = now()
			WHERE source_key = 'rtms-map-marker-2'
			""").update();
        JdbcMapMarkerRepository repository = mapMarkerRepository();

        assertThat(repository.findComplexMarkers(request(null, null)))
                .singleElement()
                .extracting(ComplexMarkerResult::latestDealAmount)
                .isEqualTo(111000L);
    }

    @Test
    @DisplayName("재건축 필지 마커는 현재 세대 complex 기준 좌표와 거래값을 반환한다")
    void redevelopmentMarkerUsesCurrentGenerationComplexCoordinateAndTrade() {
        seedRedevelopmentParcel();
        JdbcMapMarkerRepository repository = mapMarkerRepository();

        assertThat(repository.findComplexMarkers(request(null, null)))
                .extracting(
                        ComplexMarkerResult::parcelId,
                        ComplexMarkerResult::complexId,
                        ComplexMarkerResult::lat,
                        ComplexMarkerResult::lng,
                        ComplexMarkerResult::latestDealAmount,
                        ComplexMarkerResult::unitCntSum)
                .containsExactly(tuple(2001L, 802L, 37.6010, 127.1010, 190000L, 900L));
    }

    @Test
    @DisplayName("동시 존재 단지가 building 좌표로 확정되면 complex별 marker를 반환한다")
    void concurrentComplexesWithBuildingCoordinatesReturnComplexLevelMarkers() {
        seedConcurrentComplexMarkers();
        JdbcMapMarkerRepository repository = mapMarkerRepository();

        assertThat(repository.findComplexMarkers(request(null, null)))
                .extracting(
                        ComplexMarkerResult::parcelId,
                        ComplexMarkerResult::complexId,
                        ComplexMarkerResult::lat,
                        ComplexMarkerResult::lng,
                        ComplexMarkerResult::latestDealAmount,
                        ComplexMarkerResult::unitCntSum)
                .containsExactly(
                        tuple(3001L, 901L, 37.6110, 127.1110, 150000L, 410L),
                        tuple(3001L, 902L, 37.6120, 127.1120, 220000L, 520L));
    }

    @Test
    @DisplayName("3세대+ 순차 재건축 마커는 현재 세대 단위수만 반영하고 철거 세대를 합산하지 않는다")
    void multiGenerationRedevelopmentMarkerMustNotSumDemolishedUnits() {
        seedMultiGenerationRedevelopmentParcel();
        JdbcMapMarkerRepository repository = mapMarkerRepository();

        assertThat(repository.findComplexMarkers(request(null, null)))
                .extracting(ComplexMarkerResult::parcelId, ComplexMarkerResult::unitCntSum)
                .containsExactly(tuple(4001L, 900L));
    }

    @Test
    @DisplayName("동시 존재 단지의 building 좌표 confidence가 낮으면 parcel fallback marker를 반환한다")
    void concurrentComplexesWithLowConfidenceBuildingCoordinatesReturnParcelFallbackMarker() {
        seedLowConfidenceConcurrentComplexMarkers();
        JdbcMapMarkerRepository repository = mapMarkerRepository();

        assertThat(repository.findComplexMarkers(request(null, null)))
                .extracting(
                        ComplexMarkerResult::parcelId,
                        ComplexMarkerResult::complexId,
                        ComplexMarkerResult::lat,
                        ComplexMarkerResult::lng,
                        ComplexMarkerResult::latestDealAmount,
                        ComplexMarkerResult::unitCntSum)
                .containsExactly(tuple(5001L, null, 37.5133, 127.0466, 210000L, 900L));
    }

    @Test
    @DisplayName("동시 존재 단지의 building 좌표 confidence가 섞이면 확정 marker와 fallback marker를 분리한다")
    void concurrentComplexesWithMixedConfidenceBuildingCoordinatesReturnPartialSplitMarkers() {
        seedMixedConfidenceConcurrentComplexMarkers();
        JdbcMapMarkerRepository repository = mapMarkerRepository();

        assertThat(repository.findComplexMarkers(request(null, null)))
                .extracting(
                        ComplexMarkerResult::parcelId,
                        ComplexMarkerResult::complexId,
                        ComplexMarkerResult::lat,
                        ComplexMarkerResult::lng,
                        ComplexMarkerResult::latestDealAmount,
                        ComplexMarkerResult::unitCntSum)
                .containsExactly(
                        tuple(6001L, 1201L, 37.6310, 127.1310, 145000L, 420L),
                        tuple(6001L, null, 37.5144, 127.0477, 215000L, 510L));
    }

    @Test
    @DisplayName("고신뢰 building 좌표가 이미 있으면 coordinate case가 없어도 확정 complex marker를 분리한다")
    void trustedBuildingCoordinatesWithoutCoordinateCaseReturnSplitMarkers() {
        seedTrustedBuildingCoordinatesWithoutCoordinateCase();
        JdbcMapMarkerRepository repository = mapMarkerRepository();

        assertThat(repository.findComplexMarkers(request(null, null)))
                .extracting(
                        ComplexMarkerResult::parcelId,
                        ComplexMarkerResult::complexId,
                        ComplexMarkerResult::lat,
                        ComplexMarkerResult::lng,
                        ComplexMarkerResult::latestDealAmount,
                        ComplexMarkerResult::unitCntSum)
                .containsExactly(
                        tuple(8001L, 1401L, 37.6510, 127.1510, 155000L, 430L),
                        tuple(8001L, null, 37.5166, 127.0499, 225000L, 530L));
    }

    @Test
    @DisplayName("split marker 좌표가 bounds 밖이면 parcel이 bounds 안이어도 반환하지 않는다")
    void splitMarkerCoordinateOutsideBoundsIsExcluded() {
        seedTrustedBuildingCoordinateOutsideBounds();
        JdbcMapMarkerRepository repository = mapMarkerRepository();

        assertThat(repository.findComplexMarkers(request(null, null)))
                .extracting(
                        ComplexMarkerResult::parcelId,
                        ComplexMarkerResult::complexId,
                        ComplexMarkerResult::lat,
                        ComplexMarkerResult::lng,
                        ComplexMarkerResult::latestDealAmount,
                        ComplexMarkerResult::unitCntSum)
                .containsExactly(tuple(8101L, null, 37.5167, 127.0498, 226000L, 540L));
    }

    @Test
    @DisplayName("LOW confidence 재건축 필지는 현재 세대 확정 marker로 노출하지 않는다")
    void lowConfidenceRedevelopmentReturnsParcelFallbackMarker() {
        seedLowConfidenceRedevelopmentParcel();
        JdbcMapMarkerRepository repository = mapMarkerRepository();

        assertThat(repository.findComplexMarkers(request(null, null)))
                .extracting(
                        ComplexMarkerResult::parcelId,
                        ComplexMarkerResult::complexId,
                        ComplexMarkerResult::lat,
                        ComplexMarkerResult::lng,
                        ComplexMarkerResult::latestDealAmount,
                        ComplexMarkerResult::unitCntSum)
                .containsExactly(tuple(7001L, null, 37.5155, 127.0488, 190000L, 1400L));
    }

    private ComplexMarkerQuery request(Double priceEokMin, Double priceEokMax) {
        return new ComplexMarkerQuery(
                37.45, 126.85, 37.70, 127.20, null, null, priceEokMin, priceEokMax, null, null, null, null);
    }

    private ComplexMarkerQuery unitRequest(Long unitMin, Long unitMax) {
        return new ComplexMarkerQuery(
                37.45, 126.85, 37.70, 127.20, null, null, null, null, null, null, unitMin, unitMax);
    }

    private ComplexMarkerQuery shapeFilterRequest() {
        return new ComplexMarkerQuery(37.45, 126.85, 37.70, 127.20, null, null, null, null, 0, null, 0L, null);
    }

    private ComplexMarkerQuery combinedFilterRequest() {
        return new ComplexMarkerQuery(37.45, 126.85, 37.70, 127.20, 25, 26, 12.0, 13.0, 10, 30, 800L, 900L);
    }

    private ComplexMarkerQuery ratioRequest(String bcMin, String bcMax, String vlMin, String vlMax) {
        return new ComplexMarkerQuery(
                37.45,
                126.85,
                37.70,
                127.20,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new BigDecimal(bcMin),
                new BigDecimal(bcMax),
                new BigDecimal(vlMin),
                new BigDecimal(vlMax));
    }

    private void seedPublishedRatioFallback(Long complexId, BigDecimal bcRat, BigDecimal vlRat) {
        jdbcClient.sql("""
            INSERT INTO building_register_collection_campaign
              (collection_id,mode,strategy,to_complex_id,status,purpose,target_scope,selection_seed,sample_size)
            VALUES ('123e4567-e89b-12d3-a456-426614174300','profile','COMPARE_RECAP_TITLE',1000,'COLLECTING',
                    'PROFILE_DISCOVERY','NATIONWIDE_STAGING','map-ratio',1)
            """).update();
        jdbcClient.sql("""
            INSERT INTO building_register_profile_parse_run
              (parse_run_id,source_collection_id,parser_version,status)
            VALUES ('123e4567-e89b-12d3-a456-426614174301','123e4567-e89b-12d3-a456-426614174300','PROFILE_V1','RUNNING')
            """).update();
        jdbcClient.sql("""
            INSERT INTO building_register_profile_analysis_run
              (analysis_run_id,collection_id,parse_run_id,rules_version,status)
            VALUES ('123e4567-e89b-12d3-a456-426614174302','123e4567-e89b-12d3-a456-426614174300',
                    '123e4567-e89b-12d3-a456-426614174301','RULES_V1','RUNNING')
            """).update();
        jdbcClient.sql("""
            INSERT INTO building_register_profile_projection_run
              (projection_run_id,analysis_run_id,collection_id,parse_run_id,projection_version,minimum_readiness,status)
            VALUES ('123e4567-e89b-12d3-a456-426614174303','123e4567-e89b-12d3-a456-426614174302',
                    '123e4567-e89b-12d3-a456-426614174300','123e4567-e89b-12d3-a456-426614174301',
                    'PROJECTION_V1',0.5,'RUNNING')
            """).update();
        jdbcClient.sql("""
            INSERT INTO building_register_profile_publication
              (publication_id,source_collection_id,source_parse_run_id,source_analysis_run_id,source_projection_run_id,
               rules_version,parser_version,status,expected_site_count,expected_building_count,
               expected_hierarchy_count,expected_evidence_count,expected_summary_count,
               content_sha256,validated_at,published_at)
            VALUES ('123e4567-e89b-12d3-a456-426614174304','123e4567-e89b-12d3-a456-426614174300',
                    '123e4567-e89b-12d3-a456-426614174301','123e4567-e89b-12d3-a456-426614174302',
                    '123e4567-e89b-12d3-a456-426614174303','PUBLIC_V1','PROFILE_V1','PUBLISHED',0,0,0,0,0,
                    repeat('a',64),now(),now())
            """).update();
        jdbcClient
                .sql("""
            INSERT INTO complex_building_register_profile_summary
              (publication_id,complex_id,ratio_scope,ratio_quality,building_coverage_rate,floor_area_ratio)
            VALUES ('123e4567-e89b-12d3-a456-426614174304',:complex,'PARCEL','PNU_FALLBACK',:bc,:vl)
            """)
                .param("complex", complexId)
                .param("bc", bcRat)
                .param("vl", vlRat)
                .update();
    }

    private void seedRedevelopmentParcel() {
        jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (2001, 1, '1168010300101400009', 'Redeveloped lot', 37.5123, 127.0456)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt, use_date)
			VALUES
			    (801, 2001, 'COMPLEX-PK-801', 'APT-801', 'Old Mansion', 500, DATE '1985-01-01'),
			    (802, 2001, 'COMPLEX-PK-802', 'APT-802', 'New Tower', 900, DATE '2022-06-01')
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex_coordinate_case (parcel_id, pnu, status, relation_type, relation_confidence)
			VALUES (2001, '1168010300101400009', 'SKIPPED', 'REDEVELOPED', 'HIGH')
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex_display_coordinate (
			    complex_id,
			    latitude,
			    longitude,
			    coordinate_source,
			    confidence,
			    reason
			)
			VALUES
			    (801, 37.5990, 127.0990, 'PARCEL_FALLBACK', 40, 'old fallback'),
			    (802, 37.6010, 127.1010, 'BUILDING_FOOTPRINT', 90, 'new representative footprint')
			""").update();
        Long rawId = insertRawIngest("rtms-redev");
        insertTrade(rawId, 801L, LocalDate.of(2016, 1, 1), 80000L, "rtms-redev-801-1", "COMPLEX-PK-801");
        insertTrade(rawId, 801L, LocalDate.of(2026, 1, 1), 91000L, "rtms-redev-801-2", "COMPLEX-PK-801");
        insertTrade(rawId, 802L, LocalDate.of(2023, 1, 1), 180000L, "rtms-redev-802-1", "COMPLEX-PK-802");
        insertTrade(rawId, 802L, LocalDate.of(2025, 1, 1), 190000L, "rtms-redev-802-2", "COMPLEX-PK-802");
    }

    private void seedConcurrentComplexMarkers() {
        jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (3001, 1, '1168010300101400010', 'Concurrent lot', 37.5123, 127.0456)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt, use_date)
			VALUES
			    (901, 3001, 'COMPLEX-PK-901', 'APT-901', 'Concurrent A', 410, DATE '2018-01-01'),
			    (902, 3001, 'COMPLEX-PK-902', 'APT-902', 'Concurrent B', 520, DATE '2020-01-01')
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex_coordinate_case (parcel_id, pnu, status, relation_type, relation_confidence)
			VALUES (3001, '1168010300101400010', 'RESOLVED', 'CONCURRENT', 'HIGH')
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex_display_coordinate (
			    complex_id,
			    latitude,
			    longitude,
			    coordinate_source,
			    confidence,
			    reason
			)
			VALUES
			    (901, 37.6110, 127.1110, 'BUILDING_FOOTPRINT', 90, 'tower A footprint'),
			    (902, 37.6120, 127.1120, 'BUILDING_FOOTPRINT', 90, 'tower B footprint')
			""").update();
        Long rawId = insertRawIngest("rtms-concurrent");
        insertTrade(rawId, 901L, LocalDate.of(2025, 1, 1), 150000L, "rtms-concurrent-901", "COMPLEX-PK-901");
        insertTrade(rawId, 902L, LocalDate.of(2025, 2, 1), 220000L, "rtms-concurrent-902", "COMPLEX-PK-902");
    }

    private void seedMultiGenerationRedevelopmentParcel() {
        jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (4001, 1, '1168010300101400011', 'Multi-generation redevelopment lot', 37.5123, 127.0456)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt, use_date)
			VALUES
			    (1101, 4001, 'COMPLEX-PK-1101', 'APT-1101', 'Gen1 demolished', 300, DATE '1985-01-01'),
			    (1102, 4001, 'COMPLEX-PK-1102', 'APT-1102', 'Gen2 demolished', 500, DATE '2000-01-01'),
			    (1103, 4001, 'COMPLEX-PK-1103', 'APT-1103', 'Gen3 current', 900, DATE '2020-01-01')
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex_coordinate_case (parcel_id, pnu, status, relation_type, relation_confidence)
			VALUES (4001, '1168010300101400011', 'SKIPPED', 'REDEVELOPED', 'HIGH')
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex_display_coordinate (
			    complex_id,
			    latitude,
			    longitude,
			    coordinate_source,
			    confidence,
			    reason
			)
			VALUES
			    (1101, 37.5101, 127.0101, 'PARCEL_FALLBACK', 40, 'gen1 fallback'),
			    (1102, 37.5102, 127.0102, 'PARCEL_FALLBACK', 40, 'gen2 fallback'),
			    (1103, 37.5103, 127.0103, 'PARCEL_FALLBACK', 40, 'gen3 fallback')
			""").update();
        Long rawId = insertRawIngest("rtms-multigen");
        insertTrade(rawId, 1101L, LocalDate.of(2010, 1, 1), 60000L, "rtms-multigen-1101-1", "COMPLEX-PK-1101");
        insertTrade(rawId, 1101L, LocalDate.of(2012, 1, 1), 65000L, "rtms-multigen-1101-2", "COMPLEX-PK-1101");
        insertTrade(rawId, 1102L, LocalDate.of(2014, 1, 1), 90000L, "rtms-multigen-1102-1", "COMPLEX-PK-1102");
        insertTrade(rawId, 1102L, LocalDate.of(2016, 1, 1), 95000L, "rtms-multigen-1102-2", "COMPLEX-PK-1102");
        insertTrade(rawId, 1103L, LocalDate.of(2022, 1, 1), 180000L, "rtms-multigen-1103-1", "COMPLEX-PK-1103");
        insertTrade(rawId, 1103L, LocalDate.of(2025, 1, 1), 190000L, "rtms-multigen-1103-2", "COMPLEX-PK-1103");
    }

    private void seedLowConfidenceConcurrentComplexMarkers() {
        jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (5001, 1, '1168010300101400012', 'Low confidence concurrent lot', 37.5133, 127.0466)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt, use_date)
			VALUES
			    (1101, 5001, 'COMPLEX-PK-1101', 'APT-1101', 'Low Confidence A', 400, DATE '2019-01-01'),
			    (1102, 5001, 'COMPLEX-PK-1102', 'APT-1102', 'Low Confidence B', 500, DATE '2020-01-01')
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex_coordinate_case (parcel_id, pnu, status, relation_type, relation_confidence)
			VALUES (5001, '1168010300101400012', 'RESOLVED', 'CONCURRENT', 'HIGH')
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex_display_coordinate (
			    complex_id,
			    latitude,
			    longitude,
			    coordinate_source,
			    confidence,
			    reason
			)
			VALUES
			    (1101, 37.6210, 127.1210, 'BUILDING_FOOTPRINT', 60, 'low confidence tower A footprint'),
			    (1102, 37.6220, 127.1220, 'BUILDING_FOOTPRINT', 60, 'low confidence tower B footprint')
			""").update();
        Long rawId = insertRawIngest("rtms-low-confidence-concurrent");
        insertTrade(rawId, 1101L, LocalDate.of(2025, 1, 1), 140000L, "rtms-low-confidence-1101", "COMPLEX-PK-1101");
        insertTrade(rawId, 1102L, LocalDate.of(2025, 2, 1), 210000L, "rtms-low-confidence-1102", "COMPLEX-PK-1102");
    }

    private void seedMixedConfidenceConcurrentComplexMarkers() {
        jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (6001, 1, '1168010300101400013', 'Mixed confidence concurrent lot', 37.5144, 127.0477)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt, use_date)
			VALUES
			    (1201, 6001, 'COMPLEX-PK-1201', 'APT-1201', 'Mixed Confidence A', 420, DATE '2019-01-01'),
			    (1202, 6001, 'COMPLEX-PK-1202', 'APT-1202', 'Mixed Confidence B', 510, DATE '2020-01-01')
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex_coordinate_case (parcel_id, pnu, status, relation_type, relation_confidence)
			VALUES (6001, '1168010300101400013', 'RESOLVED', 'CONCURRENT', 'HIGH')
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex_display_coordinate (
			    complex_id,
			    latitude,
			    longitude,
			    coordinate_source,
			    confidence,
			    reason
			)
			VALUES
			    (1201, 37.6310, 127.1310, 'BUILDING_FOOTPRINT', 90, 'trusted tower A footprint'),
			    (1202, 37.6320, 127.1320, 'BUILDING_FOOTPRINT', 60, 'low confidence tower B footprint')
			""").update();
        Long rawId = insertRawIngest("rtms-mixed-confidence-concurrent");
        insertTrade(rawId, 1201L, LocalDate.of(2025, 1, 1), 145000L, "rtms-mixed-confidence-1201", "COMPLEX-PK-1201");
        insertTrade(rawId, 1202L, LocalDate.of(2025, 2, 1), 215000L, "rtms-mixed-confidence-1202", "COMPLEX-PK-1202");
    }

    private void seedTrustedBuildingCoordinatesWithoutCoordinateCase() {
        jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (8001, 1, '1168010300101400015', 'Projected coordinate lot', 37.5166, 127.0499)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt, use_date)
			VALUES
			    (1401, 8001, 'COMPLEX-PK-1401', 'APT-1401', 'Projected A', 430, DATE '2019-01-01'),
			    (1402, 8001, 'COMPLEX-PK-1402', 'APT-1402', 'Projected B', 530, DATE '2020-01-01')
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex_display_coordinate (
			    complex_id,
			    latitude,
			    longitude,
			    coordinate_source,
			    confidence,
			    reason
			)
			VALUES (1401, 37.6510, 127.1510, 'BUILDING_FOOTPRINT', 90, 'projected trusted footprint')
			""").update();
        Long rawId = insertRawIngest("rtms-projected-coordinate");
        insertTrade(rawId, 1401L, LocalDate.of(2025, 1, 1), 155000L, "rtms-projected-1401", "COMPLEX-PK-1401");
        insertTrade(rawId, 1402L, LocalDate.of(2025, 2, 1), 225000L, "rtms-projected-1402", "COMPLEX-PK-1402");
    }

    private void seedTrustedBuildingCoordinateOutsideBounds() {
        jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (8101, 1, '1168010300101400016', 'Projected coordinate outside bounds lot', 37.5167, 127.0498)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt, use_date)
			VALUES
			    (1501, 8101, 'COMPLEX-PK-1501', 'APT-1501', 'Outside Projected A', 440, DATE '2019-01-01'),
			    (1502, 8101, 'COMPLEX-PK-1502', 'APT-1502', 'Inside Fallback B', 540, DATE '2020-01-01')
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex_display_coordinate (
			    complex_id,
			    latitude,
			    longitude,
			    coordinate_source,
			    confidence,
			    reason
			)
			VALUES (1501, 37.7510, 127.2510, 'BUILDING_FOOTPRINT', 90, 'trusted footprint outside current bounds')
			""").update();
        Long rawId = insertRawIngest("rtms-projected-outside-bounds");
        insertTrade(rawId, 1501L, LocalDate.of(2025, 1, 1), 156000L, "rtms-projected-outside-1501", "COMPLEX-PK-1501");
        insertTrade(rawId, 1502L, LocalDate.of(2025, 2, 1), 226000L, "rtms-projected-outside-1502", "COMPLEX-PK-1502");
    }

    private void seedLowConfidenceRedevelopmentParcel() {
        jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (7001, 1, '1168010300101400014', 'Low confidence redevelopment lot', 37.5155, 127.0488)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt, use_date)
			VALUES
			    (1301, 7001, 'COMPLEX-PK-1301', 'APT-1301', 'Low Old Mansion', 500, DATE '1985-01-01'),
			    (1302, 7001, 'COMPLEX-PK-1302', 'APT-1302', 'Low New Tower', 900, DATE '2022-06-01')
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex_coordinate_case (parcel_id, pnu, status, relation_type, relation_confidence)
			VALUES (7001, '1168010300101400014', 'SKIPPED', 'REDEVELOPED', 'LOW')
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex_display_coordinate (
			    complex_id,
			    latitude,
			    longitude,
			    coordinate_source,
			    confidence,
			    reason
			)
			VALUES
			    (1301, 37.6410, 127.1410, 'PARCEL_FALLBACK', 40, 'old fallback'),
			    (1302, 37.6420, 127.1420, 'BUILDING_FOOTPRINT', 90, 'new footprint not enough for LOW redevelopment')
			""").update();
        Long rawId = insertRawIngest("rtms-low-redev");
        insertTrade(rawId, 1301L, LocalDate.of(2016, 1, 1), 80000L, "rtms-low-redev-1301", "COMPLEX-PK-1301");
        insertTrade(rawId, 1302L, LocalDate.of(2025, 1, 1), 190000L, "rtms-low-redev-1302", "COMPLEX-PK-1302");
    }

    private void seedMapData() {
        jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES
			    (1001, 1, '1168010300101400001', 'Inside bounds', 37.5123, 127.0456),
			    (1002, 1, '1168010300101400002', 'Outside bounds', 37.8123, 127.4456)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt)
			VALUES
			    (501, 1001, 'COMPLEX-PK-501', 'APT-501', 'Sample Apartment A', 740),
			    (502, 1001, 'COMPLEX-PK-502', 'APT-502', 'Sample Apartment B', 120),
			    (503, 1002, 'COMPLEX-PK-503', 'APT-503', 'Out-of-bounds Apartment', 300)
			""").update();
        Long rawId = insertRawIngest("rtms-map-marker-1");
        insertTrade(rawId, 501L, LocalDate.of(2025, 11, 1), 111000L, "rtms-map-marker-1", "COMPLEX-PK-501");
        insertTrade(rawId, 502L, LocalDate.of(2025, 12, 1), 125000L, "rtms-map-marker-2", "COMPLEX-PK-502");
        insertTrade(rawId, 503L, LocalDate.of(2025, 12, 2), 200000L, "rtms-map-marker-3", "COMPLEX-PK-503");
    }

    private void seedCoordinatePendingMapData() {
        jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1001, 1, '1168010300108880001', 'Coordinate pending', NULL, NULL)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt)
			VALUES (501, 1001, 'RTMS:APT-PENDING-501', 'APT-PENDING-501', 'Coordinate Pending Apartment', 740)
			""").update();
        Long rawId = insertRawIngest("rtms-coordinate-pending-marker");
        insertTrade(
                rawId,
                501L,
                LocalDate.of(2025, 12, 1),
                125000L,
                "rtms-coordinate-pending-marker",
                "RTMS:APT-PENDING-501");
    }

    private void seedMissingUnitCountMapData() {
        jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (9001, 1, '1168010300101400017', 'Missing unit count lot', 37.5123, 127.0456)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt)
			VALUES (1901, 9001, 'COMPLEX-PK-1901', 'APT-1901', 'Missing Unit Count Apartment', NULL)
			""").update();
        Long rawId = insertRawIngest("rtms-missing-unit-count-marker");
        insertTrade(
                rawId, 1901L, LocalDate.of(2025, 12, 1), 125000L, "rtms-missing-unit-count-marker", "COMPLEX-PK-1901");
    }

    private Long insertRawIngest(String sourceKey) {
        return jdbcClient
                .sql("""
			INSERT INTO raw_trade_ingest (
			    source,
			    source_key,
			    lawd_cd,
			    deal_ymd,
			    page_no,
			    payload,
			    payload_hash,
			    status
			)
			VALUES ('RTMS', :sourceKey, '11680', '202512', 1, '{}', :payloadHash, 'NORMALIZED')
			RETURNING id
			""")
                .param("sourceKey", sourceKey)
                .param("payloadHash", "payload-hash-" + sourceKey)
                .query(Long.class)
                .single();
    }

    private void insertTrade(
            Long rawId, Long complexId, LocalDate dealDate, Long dealAmount, String sourceKey, String complexPk) {
        jdbcClient
                .sql("""
			INSERT INTO trade (
			    raw_ingest_id,
			    complex_id,
			    deal_date,
			    deal_amount,
			    floor,
			    excl_area,
			    apt_dong,
			    source,
			    source_key,
			    complex_pk,
			    apt_seq
			)
			VALUES (
			    :rawId,
			    :complexId,
			    :dealDate,
			    :dealAmount,
			    12,
			    84.93,
			    '101',
			    'RTMS',
			    :sourceKey,
			    :complexPk,
			    'APT-501'
			)
			""")
                .param("rawId", rawId)
                .param("complexId", complexId)
                .param("dealDate", dealDate)
                .param("dealAmount", dealAmount)
                .param("sourceKey", sourceKey)
                .param("complexPk", complexPk)
                .update();
    }
}
