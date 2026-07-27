package com.home.infrastructure.persistence.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.home.application.read.BuildingProfileSummaryResult;
import com.home.application.read.ComplexSuggestionResult;
import com.home.application.read.ComplexSummaryResult;
import com.home.application.read.ParcelDetailResult;
import com.home.application.read.RegionDetailResult;
import com.home.application.read.RegionSummaryResult;
import com.home.application.read.SearchComplexResult;
import com.home.application.read.TradeAreasResult;
import com.home.application.read.TradeListResult;
import com.home.application.read.TradeTrendPoint;
import com.home.domain.complex.buildingprofile.BuildingProfilePublicQuality;
import com.home.domain.complex.buildingprofile.BuildingProfilePublicScope;
import com.home.domain.complex.buildingprofile.BuildingProfileSeismicDesignStatus;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;
import com.home.infrastructure.persistence.propertydetail.JdbcPropertyDetailReader;
import com.home.infrastructure.persistence.regionnavigation.JdbcRegionNavigationReader;
import com.home.infrastructure.persistence.search.JdbcComplexSearchReader;
import com.home.infrastructure.persistence.tradehistory.JdbcTradeHistoryReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcReadCapabilityReadersTest extends JdbcPostgresTestSupport {

    private static final String PROFILE_PUBLICATION_ID = "10000000-0000-0000-0000-000000000005";

    @Test
    @DisplayName("search/region/detail/trade read API는 baseline core table로 backing된다")
    void readsPropertyMapExplorationDataFromBaselineTables() {
        seedPropertyExplorationData();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.searchComplexes("sample")).singleElement().satisfies(result -> {
            assertThat(result.complexId()).isEqualTo(501L);
            assertThat(result.complexName()).isEqualTo("Sample trade name");
            assertThat(result.parcelId()).isEqualTo(1001L);
            assertThat(result.latitude()).isEqualTo(37.5123);
            assertThat(result.longitude()).isEqualTo(127.0456);
            assertThat(result.address()).isEqualTo("Sample address");
        });

        assertThat(repository.findRootRegions()).singleElement().satisfies(region -> {
            assertThat(region.id()).isEqualTo(1L);
            assertThat(region.name()).isEqualTo("Seoul");
        });

        assertThat(repository.findRegionDetail(1L)).hasValueSatisfying(region -> {
            assertThat(region.latitude()).isEqualTo(37.5663);
            assertThat(region.longitude()).isEqualTo(126.9780);
            assertThat(region.children()).extracting("name").containsExactly("Gangnam-gu");
        });

        assertThat(repository.findParcelDetail(1001L, null)).hasValueSatisfying(detail -> {
            assertThat(detail.parcelId()).isEqualTo(1001L);
            assertThat(detail.name()).isEqualTo("Sample Apartment");
            assertThat(detail.tradeName()).isEqualTo("Sample trade name");
            assertThat(detail.unitCnt()).isEqualTo(740);
            assertThat(detail.platArea()).isEqualByComparingTo(new BigDecimal("12345.67"));
            assertThat(detail.useDate()).isEqualTo(LocalDate.of(2015, 3, 20));
        });

        assertThat(repository.findTradeList(1001L, null, 0, 25)).hasValueSatisfying(tradeList -> {
            assertThat(tradeList.parcelId()).isEqualTo(1001L);
            assertThat(tradeList.trades()).extracting("tradeId").containsExactly(9002L, 9001L);
            assertThat(tradeList.trades().get(0).dealAmount()).isEqualTo(130000L);
        });
    }

    @Test
    @DisplayName("detail read API는 발행된 건축물 profile의 모든 공개 section을 typed 값으로 읽는다")
    void readsEveryPublishedBuildingProfileSection() {
        seedComplex();
        seedPublishedBuildingProfile();
        JdbcPropertyDetailReader repository = new JdbcPropertyDetailReader(jdbcClient);

        assertThat(repository.findComplexDetail(501L))
                .get()
                .extracting(ParcelDetailResult::buildingProfile)
                .satisfies(profile -> {
                    assertThat(profile.ratios()).satisfies(ratios -> {
                        assertThat(ratios.scope()).isEqualTo(BuildingProfilePublicScope.COMPLEX);
                        assertThat(ratios.quality()).isEqualTo(BuildingProfilePublicQuality.VERIFIED);
                        assertThat(ratios.buildingCoverageRate()).isEqualByComparingTo("27.5");
                        assertThat(ratios.floorAreaRatio()).isEqualByComparingTo("210.4");
                        assertThat(ratios.siteAreaM2()).isEqualByComparingTo("1000");
                        assertThat(ratios.buildingAreaM2()).isEqualByComparingTo("275");
                        assertThat(ratios.totalFloorAreaM2()).isEqualByComparingTo("2400");
                        assertThat(ratios.floorAreaRatioAreaM2()).isEqualByComparingTo("2104");
                    });
                    assertThat(profile.households())
                            .extracting(
                                    BuildingProfileSummaryResult.Households::scope,
                                    BuildingProfileSummaryResult.Households::quality,
                                    BuildingProfileSummaryResult.Households::householdCount,
                                    BuildingProfileSummaryResult.Households::familyCount,
                                    BuildingProfileSummaryResult.Households::unitCount)
                            .containsExactly(
                                    BuildingProfilePublicScope.PARCEL,
                                    BuildingProfilePublicQuality.PNU_FALLBACK,
                                    740L,
                                    12L,
                                    760L);
                    assertThat(profile.parking()).satisfies(parking -> {
                        assertThat(parking.totalCount()).isEqualTo(0L);
                        assertThat(parking.perHousehold()).isEqualByComparingTo("1.25");
                        assertThat(parking.indoorMechanicalCount()).isEqualTo(1L);
                        assertThat(parking.outdoorMechanicalCount()).isEqualTo(2L);
                        assertThat(parking.indoorAutomaticCount()).isEqualTo(3L);
                        assertThat(parking.outdoorAutomaticCount()).isEqualTo(4L);
                        assertThat(parking.indoorMechanicalAreaM2()).isEqualByComparingTo("10.1");
                        assertThat(parking.outdoorMechanicalAreaM2()).isEqualByComparingTo("20.2");
                        assertThat(parking.indoorAutomaticAreaM2()).isEqualByComparingTo("30.3");
                        assertThat(parking.outdoorAutomaticAreaM2()).isEqualByComparingTo("40.4");
                    });
                    assertThat(profile.building()).satisfies(building -> {
                        assertThat(building.quality()).isEqualTo(BuildingProfilePublicQuality.PARTIAL);
                        assertThat(building.mainBuildingCount()).isEqualTo(8L);
                        assertThat(building.attachedBuildingCount()).isEqualTo(2L);
                        assertThat(building.maxGroundFloorCount()).isEqualTo(25L);
                        assertThat(building.maxUndergroundFloorCount()).isEqualTo(3L);
                        assertThat(building.maxHeightM()).isEqualByComparingTo("82.4");
                        assertThat(building.structures()).containsExactly("철근콘크리트");
                        assertThat(building.roofs()).containsExactly("평지붕");
                        assertThat(building.primaryUses()).containsExactly("공동주택", "근린생활시설");
                    });
                    assertThat(profile.elevators())
                            .extracting(
                                    BuildingProfileSummaryResult.Elevators::rideUseCount,
                                    BuildingProfileSummaryResult.Elevators::emergencyUseCount)
                            .containsExactly(12L, 4L);
                    assertThat(profile.safety()).satisfies(safety -> {
                        assertThat(safety.seismicDesignStatus())
                                .isEqualTo(BuildingProfileSeismicDesignStatus.ALL_APPLIED);
                        assertThat(safety.seismicAbilities()).containsExactly("VII-0.176g");
                    });
                    assertThat(profile.dates())
                            .extracting(
                                    BuildingProfileSummaryResult.Dates::permitDate,
                                    BuildingProfileSummaryResult.Dates::constructionStartDate,
                                    BuildingProfileSummaryResult.Dates::useApprovalDate)
                            .containsExactly(
                                    LocalDate.of(2010, 1, 2), LocalDate.of(2011, 3, 4), LocalDate.of(2015, 3, 20));
                    assertThat(profile.address())
                            .extracting(
                                    BuildingProfileSummaryResult.Address::parcelAddress,
                                    BuildingProfileSummaryResult.Address::roadAddress)
                            .containsExactly("서울 표본구 1", "서울 표본구 표본로 2");
                    assertThat(profile.energy()).satisfies(energy -> {
                        assertThat(energy.efficiencyGrades()).containsExactly("1등급");
                        assertThat(energy.savingRateMin()).isEqualByComparingTo("12.3");
                        assertThat(energy.savingRateMax()).isEqualByComparingTo("18.7");
                        assertThat(energy.epiMin()).isEqualByComparingTo("72.1");
                        assertThat(energy.epiMax()).isEqualByComparingTo("80.9");
                        assertThat(energy.greenGrades()).containsExactly("최우수");
                        assertThat(energy.greenScoreMin()).isEqualByComparingTo("85");
                        assertThat(energy.greenScoreMax()).isEqualByComparingTo("90");
                        assertThat(energy.intelligentGrades()).containsExactly("1등급");
                        assertThat(energy.intelligentScoreMin()).isEqualByComparingTo("88");
                        assertThat(energy.intelligentScoreMax()).isEqualByComparingTo("93");
                    });
                });
    }

    @Test
    @DisplayName("trade read API는 page/size window와 totalElements를 적용한다")
    void tradeListAppliesPageWindowAndTotalElements() {
        seedPropertyExplorationData();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.findTradeList(1001L, null, 0, 1)).hasValueSatisfying(tradeList -> {
            assertThat(tradeList.page()).isEqualTo(0);
            assertThat(tradeList.size()).isEqualTo(1);
            assertThat(tradeList.totalElements()).isEqualTo(2L);
            assertThat(tradeList.trades()).extracting("tradeId").containsExactly(9002L);
        });

        assertThat(repository.findTradeList(1001L, null, 1, 1)).hasValueSatisfying(tradeList -> {
            assertThat(tradeList.page()).isEqualTo(1);
            assertThat(tradeList.totalElements()).isEqualTo(2L);
            assertThat(tradeList.trades()).extracting("tradeId").containsExactly(9001L);
        });
    }

    @Test
    @DisplayName("trade area와 exact filter는 active positive NUMERIC 면적만 같은 기준으로 조회한다")
    void tradeAreasAndExactFilterUseActivePositiveNumericRows() {
        seedPropertyExplorationData();
        jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    id, source, source_key, lawd_cd, deal_ymd, page_no, payload, payload_hash, status, processed_at
			) VALUES
			    (90003, 'RTMS', 'area-59', '11680', '202606', 1, '{}', 'area-hash-59', 'NORMALIZED', now()),
			    (90004, 'RTMS', 'area-97', '11680', '202606', 1, '{}', 'area-hash-97', 'NORMALIZED', now()),
			    (90005, 'RTMS', 'area-deleted', '11680', '202607', 1, '{}', 'area-hash-deleted', 'NORMALIZED', now()),
			    (90006, 'RTMS', 'area-zero', '11680', '202607', 1, '{}', 'area-hash-zero', 'NORMALIZED', now())
			""").update();
        jdbcClient.sql("""
			INSERT INTO trade (
			    id, complex_id, deal_date, deal_amount, floor, excl_area, apt_dong,
			    source, source_key, complex_pk, apt_seq, raw_ingest_id, deleted_at
			) VALUES
			    (9003, 501, DATE '2026-06-01', 90000, 7, 59.93, '101',
			     'RTMS', 'area-59', 'COMPLEX-PK-501', 'APT-501', 90003, NULL),
			    (9004, 501, DATE '2026-06-01', 150000, 18, 97.90, '101',
			     'RTMS', 'area-97', 'COMPLEX-PK-501', 'APT-501', 90004, NULL),
			    (9005, 501, DATE '2026-07-01', 200000, 20, 120.00, '101',
			     'RTMS', 'area-deleted', 'COMPLEX-PK-501', 'APT-501', 90005, now()),
			    (9006, 501, DATE '2026-07-02', 1, 1, 0.00, '101',
			     'RTMS', 'area-zero', 'COMPLEX-PK-501', 'APT-501', 90006, NULL)
			""").update();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.findTradeAreas(501L)).hasValueSatisfying(result -> {
            assertThat(result.defaultExclArea()).isEqualByComparingTo("97.90");
            assertThat(result.areas())
                    .extracting("exclArea", "tradeCount", "latestDealDate")
                    .containsExactly(
                            tuple(new BigDecimal("59.93"), 1L, LocalDate.of(2026, 6, 1)),
                            tuple(new BigDecimal("84.93"), 2L, LocalDate.of(2025, 12, 15)),
                            tuple(new BigDecimal("97.90"), 1L, LocalDate.of(2026, 6, 1)));
        });
        assertThat(repository.findComplexTradeList(501L, new BigDecimal("84.93"), 0, 1))
                .hasValueSatisfying(result -> {
                    assertThat(result.totalElements()).isEqualTo(2);
                    assertThat(result.trades()).extracting("tradeId").containsExactly(9002L);
                });
        assertThat(repository.findComplexTradeList(501L, new BigDecimal("84.94"), 0, 25))
                .hasValueSatisfying(result -> {
                    assertThat(result.totalElements()).isZero();
                    assertThat(result.trades()).isEmpty();
                });
        assertThat(repository.findComplexTradeTrend(501L, new BigDecimal("59.93")))
                .hasValueSatisfying(trend -> assertThat(trend).singleElement().satisfies(point -> {
                    assertThat(point.month()).isEqualTo("2026-06");
                    assertThat(point.avgAmount()).isEqualTo(90000L);
                    assertThat(point.count()).isEqualTo(1);
                }));
        assertThat(repository.findComplexTradeList(501L, 0, 25))
                .hasValueSatisfying(result -> assertThat(result.totalElements()).isEqualTo(5));
    }

    @Test
    @DisplayName("trade area는 유효 무거래 단지와 없는 단지를 구분한다")
    void tradeAreasDistinguishEmptyComplexFromMissingComplex() {
        seedComplex();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.findTradeAreas(501L)).hasValueSatisfying(result -> {
            assertThat(result.defaultExclArea()).isNull();
            assertThat(result.areas()).isEmpty();
        });
        assertThat(repository.findTradeAreas(999L)).isEmpty();
    }

    @Test
    @DisplayName("trade trend read API는 월별 평균/건수/min/max를 오름차순으로 집계한다")
    void tradeTrendAggregatesMonthlyAveragesAscending() {
        seedPropertyExplorationData();
        jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    id, source, source_key, lawd_cd, deal_ymd, page_no, payload, payload_hash, status, processed_at
			) VALUES (90003, 'RTMS', 'sample-rtms-20251020', '11680', '202510', 1, '{}', 'hash-3', 'NORMALIZED', now())
			""").update();
        jdbcClient.sql("""
			INSERT INTO trade (
			    id, complex_id, deal_date, deal_amount, floor, excl_area, apt_dong, source, source_key, complex_pk, apt_seq, raw_ingest_id
			) VALUES (9003, 501, DATE '2025-10-20', 100000, 8, 84.93, '101', 'RTMS', 'sample-rtms-20251020', 'COMPLEX-PK-501', 'APT-501', 90003)
			""").update();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.findTradeTrend(1001L, null)).hasValueSatisfying(trend -> {
            assertThat(trend).extracting(TradeTrendPoint::month).containsExactly("2025-10", "2025-12");
            assertThat(trend.get(0).avgAmount()).isEqualTo(100000L);
            assertThat(trend.get(0).count()).isEqualTo(1);
            assertThat(trend.get(1).avgAmount()).isEqualTo(127500L);
            assertThat(trend.get(1).count()).isEqualTo(2);
            assertThat(trend.get(1).minAmount()).isEqualTo(125000L);
            assertThat(trend.get(1).maxAmount()).isEqualTo(130000L);
        });
        assertThat(repository.findComplexTradeTrend(501L))
                .hasValueSatisfying(trend ->
                        assertThat(trend).extracting(TradeTrendPoint::month).containsExactly("2025-10", "2025-12"));
    }

    @Test
    @DisplayName("trade trend read API는 soft-delete를 제외하고 부모 경로 없으면 empty다")
    void tradeTrendExcludesSoftDeletedAndEmptyWhenParentMissing() {
        seedPropertyExplorationData();
        jdbcClient.sql("UPDATE trade SET deleted_at = now() WHERE id = 9002").update();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.findTradeTrend(1001L, null))
                .hasValueSatisfying(trend -> assertThat(trend).singleElement().satisfies(point -> {
                    assertThat(point.count()).isEqualTo(1);
                    assertThat(point.avgAmount()).isEqualTo(125000L);
                }));
        assertThat(repository.findTradeTrend(404L, null)).isEmpty();
        assertThat(repository.findComplexTradeTrend(999L)).isEmpty();
    }

    @Test
    @DisplayName("trade read API는 parcel에 complex가 있지만 trade가 없으면 empty trade list를 반환한다")
    void tradeListReturnsEmptyWhenParcelAndComplexExistWithoutTrades() {
        seedComplex();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.findTradeList(1001L, null, 0, 25)).hasValueSatisfying(tradeList -> {
            assertThat(tradeList.parcelId()).isEqualTo(1001L);
            assertThat(tradeList.trades()).isEmpty();
        });
    }

    @Test
    @DisplayName("trade read API는 canceled trade를 목록에서 제외한다")
    void tradeListExcludesCanceledTrade() {
        seedPropertyExplorationData();
        jdbcClient.sql("""
			UPDATE trade
			SET deleted_at = now()
			WHERE id = 9002
			""").update();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.findTradeList(1001L, null, 0, 25)).hasValueSatisfying(tradeList -> {
            assertThat(tradeList.trades()).extracting("tradeId").containsExactly(9001L);
            assertThat(tradeList.totalElements()).isEqualTo(1L);
        });
    }

    @Test
    @DisplayName("detail/trade read API는 complexId가 있으면 같은 parcel의 선택 complex로 범위를 좁힌다")
    void detailAndTradeCanBeScopedToSelectedComplex() {
        seedTwoComplexParcel();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.findParcelDetail(2001L, 702L)).hasValueSatisfying(detail -> {
            assertThat(detail.parcelId()).isEqualTo(2001L);
            assertThat(detail.complexId()).isEqualTo(702L);
            assertThat(detail.name()).isEqualTo("Complex B");
            assertThat(detail.unitCnt()).isEqualTo(320);
        });
        assertThat(repository.findTradeList(2001L, 702L, 0, 25)).hasValueSatisfying(tradeList -> {
            assertThat(tradeList.parcelId()).isEqualTo(2001L);
            assertThat(tradeList.complexId()).isEqualTo(702L);
            assertThat(tradeList.trades()).extracting("tradeId").containsExactly(9702L);
        });
        assertThat(repository.findParcelDetail(2001L, 999L)).isEmpty();
        assertThat(repository.findTradeList(2001L, 999L, 0, 25)).isEmpty();
    }

    @Test
    @DisplayName("trade read API는 동일 조건이어도 aptDong이 다른 거래를 모두 반환한다")
    void tradeListKeepsSameConditionTradesWhenAptDongDiffers() {
        seedComplex();
        jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    id,
			    source,
			    source_key,
			    lawd_cd,
			    deal_ymd,
			    page_no,
			    payload,
			    payload_hash,
			    status,
			    processed_at
			)
			VALUES
			    (90001, 'RTMS', 'same-condition-101', '11680', '202512', 1, '{}', 'hash-101', 'NORMALIZED', now()),
			    (90002, 'RTMS', 'same-condition-102', '11680', '202512', 1, '{}', 'hash-102', 'NORMALIZED', now())
			""").update();
        jdbcClient.sql("""
			INSERT INTO trade (
			    id,
			    complex_id,
			    deal_date,
			    deal_amount,
			    floor,
			    excl_area,
			    apt_dong,
			    source,
			    source_key,
			    complex_pk,
			    apt_seq,
			    raw_ingest_id
			)
			VALUES
			    (9001, 501, DATE '2025-12-01', 125000, 12, 84.93, '101', 'RTMS', 'same-condition-101', 'COMPLEX-PK-501', 'APT-501', 90001),
			    (9002, 501, DATE '2025-12-01', 125000, 12, 84.93, '102', 'RTMS', 'same-condition-102', 'COMPLEX-PK-501', 'APT-501', 90002)
			""").update();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.findTradeList(1001L, null, 0, 25))
                .hasValueSatisfying(tradeList -> assertThat(tradeList.trades())
                        .extracting("tradeId", "aptDong")
                        .containsExactly(tuple(9002L, "102"), tuple(9001L, "101")));
    }

    @Test
    @DisplayName("search API complexName은 레거시처럼 trade_name을 name보다 우선한다")
    void searchComplexesUsesLegacyDisplayNamePolicy() {
        seedComplex();
        jdbcClient.sql("""
			UPDATE complex
			SET name = 'Building Register Name',
			    trade_name = 'Legacy Trade Display Name'
			WHERE id = 501
			""").update();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.searchComplexes("legacy"))
                .singleElement()
                .satisfies(result -> assertThat(result.complexName()).isEqualTo("Legacy Trade Display Name"));
    }

    @Test
    @DisplayName("search API complexName은 trade_name이 blank이면 name으로 fallback한다")
    void searchComplexesFallsBackToNameWhenTradeNameIsBlank() {
        seedComplex();
        jdbcClient.sql("""
			UPDATE complex
			SET name = 'Building Register Name',
			    trade_name = '   '
			WHERE id = 501
			""").update();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.searchComplexes("building"))
                .singleElement()
                .satisfies(result -> assertThat(result.complexName()).isEqualTo("Building Register Name"));
    }

    @Test
    @DisplayName("search API는 보존된 complex alias도 검색 evidence로 사용한다")
    void searchComplexesFindsComplexByPreservedAlias() {
        seedComplex();
        jdbcClient.sql("""
			UPDATE complex
			SET name = 'Building Register Name',
			    trade_name = 'Official Trade Name'
			WHERE id = 501
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex_name_alias (
			    complex_id,
			    alias_type,
			    alias_name,
			    normalized_name,
			    source
			)
			VALUES (
			    501,
			    'RTMS_APT_NAME',
			    'RTMS Wobbly Name',
			    'rtmswobblyname',
			    'RTMS'
			)
			""").update();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.searchComplexes("wobbly")).singleElement().satisfies(result -> {
            assertThat(result.complexId()).isEqualTo(501L);
            assertThat(result.complexName()).isEqualTo("Official Trade Name");
            assertThat(result.parcelId()).isEqualTo(1001L);
        });
        assertThat(repository.searchComplexes("rtmswobbly"))
                .singleElement()
                .satisfies(result -> assertThat(result.complexId()).isEqualTo(501L));
    }

    @Test
    @DisplayName("search API는 표시명·alias·주소의 복합 token을 순서와 무관하게 AND 검색한다")
    void searchComplexesMatchesAllTokensAcrossDisplayAliasAndAddressRegardlessOfOrder() {
        jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (28, '1120010800', '응봉동', 'eup-myeon-dong')
			""").update();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (97, 28, '1120010800100970000', '서울 성동구 응봉동 97', 37.5500, 127.0300)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, region_id, complex_pk, apt_seq, name, trade_name)
			VALUES (4677, 97, 28, 'RTMS:11200-28', '11200-28', '대림(2차)', '대림(2차)')
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex_name_alias (complex_id, alias_type, alias_name, normalized_name, source)
			VALUES (4677, 'RTMS_APT_NAME', '대림2차', '대림2차', 'RTMS')
			""").update();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.searchComplexes("응봉동 대림"))
                .extracting("complexId", "complexName")
                .containsExactly(tuple(4677L, "대림(2차)"));
        assertThat(repository.searchComplexes("대림 응봉동")).extracting("complexId").containsExactly(4677L);
        assertThat(repository.searchComplexes("응봉동 97 대림2차"))
                .extracting("complexId")
                .containsExactly(4677L);
        assertThat(repository.findRegionComplexes(28L, 20, 0))
                .hasValueSatisfying(complexes ->
                        assertThat(complexes).extracting("complexName").containsExactly("대림(2차)"));
        assertThat(repository.findParcelComplexes(97L))
                .hasValueSatisfying(complexes ->
                        assertThat(complexes).extracting("complexName").containsExactly("대림(2차)"));
        assertThat(repository.findParcelDetail(97L, 4677L)).hasValueSatisfying(detail -> {
            assertThat(detail.displayName()).isEqualTo("응봉동 대림(2차)");
            assertThat(detail.name()).isEqualTo("대림(2차)");
            assertThat(detail.tradeName()).isEqualTo("대림(2차)");
        });
    }

    @Test
    @DisplayName("search API는 %, _, \\ 문자를 SQL wildcard로 해석하지 않는다")
    void searchComplexesTreatsLikeWildcardsAsLiteralCharacters() {
        seedComplex();
        jdbcClient.sql("""
			UPDATE complex
			SET name = 'Rate 100%_Home', trade_name = 'Rate 100%_Home'
			WHERE id = 501
			""").update();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.searchComplexes("%_")).extracting("complexId").containsExactly(501L);
        assertThat(repository.searchComplexes("\\")).isEmpty();
    }

    @Test
    @DisplayName("search API는 exact, prefix, alias, address match 순서로 관련도를 우선한다")
    void searchComplexesRanksExactPrefixAliasBeforeAddressMatches() {
        seedSearchRankingData();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.searchComplexes("river"))
                .extracting("complexId", "complexName")
                .containsExactly(
                        tuple(801L, "River"),
                        tuple(802L, "River Heights"),
                        tuple(803L, "ZZZ Alias Display"),
                        tuple(804L, "AAA Address Only"));
    }

    @Test
    @DisplayName("suggestion API는 search ranking과 같은 관련도 순서를 사용하고 limit을 지킨다")
    void suggestComplexesUsesSearchRankingAndLimit() {
        seedSearchRankingData();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.suggestComplexes("river", 3))
                .extracting("complexId", "complexName")
                .containsExactly(tuple(801L, "River"), tuple(802L, "River Heights"), tuple(803L, "ZZZ Alias Display"));
    }

    @Test
    @DisplayName("search API alias substring 검색은 pg_trgm GIN index 기반을 가진다")
    void complexAliasSubstringSearchHasTrigramIndexes() {
        assertThat(extensionExists("pg_trgm")).isTrue();
        assertThat(indexDefinition("ix_complex_name_alias_normalized_name_trgm"))
                .contains("USING gin")
                .contains("normalized_name gin_trgm_ops");
        assertThat(indexDefinition("ix_complex_name_alias_alias_name_lower_trgm"))
                .contains("USING gin")
                .contains("lower((alias_name)::text) gin_trgm_ops");
    }

    @Test
    @DisplayName("search API primary substring 검색은 complex와 parcel trigram index 기반을 가진다")
    void searchComplexesPrimarySubstringSearchHasTrigramIndexes() {
        assertThat(extensionExists("pg_trgm")).isTrue();
        assertThat(indexDefinition("ix_complex_name_lower_trgm"))
                .contains("USING gin")
                .contains("lower")
                .contains("name")
                .contains("gin_trgm_ops");
        assertThat(indexDefinition("ix_complex_trade_name_lower_trgm"))
                .contains("USING gin")
                .contains("lower")
                .contains("trade_name")
                .contains("gin_trgm_ops");
        assertThat(indexDefinition("ix_parcel_address_lower_trgm"))
                .contains("USING gin")
                .contains("lower")
                .contains("address")
                .contains("gin_trgm_ops");
    }

    @Test
    @DisplayName("search API는 complex 단위 결과라 같은 parcelId를 여러 결과에서 반환할 수 있다")
    void searchComplexesCanReturnMultipleComplexesForSameParcel() {
        seedComplex();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt)
			VALUES (502, 1001, 'COMPLEX-PK-502', 'APT-502', 'Sample River Tower', 'Sample River Trade', 120)
			""").update();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.searchComplexes("sample"))
                .extracting("complexId", "parcelId")
                .containsExactly(tuple(501L, 1001L), tuple(502L, 1001L));
    }

    @Test
    @DisplayName("search/detail read API는 complex 표시 좌표가 있으면 parcel 좌표보다 우선한다")
    void searchAndDetailPreferComplexDisplayCoordinate() {
        seedPropertyExplorationData();
        jdbcClient.sql("""
			INSERT INTO complex_display_coordinate (
			    complex_id,
			    latitude,
			    longitude,
			    coordinate_source,
			    confidence,
			    reason
			)
			VALUES (501, 37.6010, 127.1010, 'PARCEL_FALLBACK', 70, 'test display coordinate')
			""").update();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.searchComplexes("sample")).singleElement().satisfies(result -> {
            assertThat(result.latitude()).isEqualTo(37.6010);
            assertThat(result.longitude()).isEqualTo(127.1010);
        });
        assertThat(repository.findParcelDetail(1001L, null)).hasValueSatisfying(detail -> {
            assertThat(detail.latitude()).isEqualTo(37.6010);
            assertThat(detail.longitude()).isEqualTo(127.1010);
        });
    }

    @Test
    @DisplayName("search/detail read API는 좌표 대기 parcel의 null 좌표를 0으로 바꾸지 않는다")
    void searchAndDetailKeepNullCoordinatesForCoordinatePendingParcel() {
        jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '4128110100', 'Sample-dong', 'eup-myeon-dong')
			""").update();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (3001, 1, '4128110100100010001', 'Coordinate pending address', NULL, NULL)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt)
			VALUES (801, 3001, 'COMPLEX-PK-801', 'APT-801', 'Coordinate Pending Complex', 'Coordinate Pending Trade', 180)
			""").update();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.searchComplexes("pending")).singleElement().satisfies(result -> {
            assertThat(result.parcelId()).isEqualTo(3001L);
            assertThat(result.latitude()).isNull();
            assertThat(result.longitude()).isNull();
        });
        assertThat(repository.findParcelDetail(3001L, 801L)).hasValueSatisfying(detail -> {
            assertThat(detail.latitude()).isNull();
            assertThat(detail.longitude()).isNull();
        });
    }

    @Test
    @DisplayName("detail은 parcel 대표 complex를 반환하고 trade는 parcel 하위 모든 complex 거래를 반환한다")
    void detailUsesRepresentativeComplexAndTradeListIncludesAllParcelComplexTrades() {
        seedPropertyExplorationData();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt)
			VALUES (502, 1001, 'COMPLEX-PK-502', 'APT-502', 'Secondary Complex', 'Secondary Trade Name', 120)
			""").update();
        jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    id,
			    source,
			    source_key,
			    lawd_cd,
			    deal_ymd,
			    page_no,
			    payload,
			    payload_hash,
			    status,
			    processed_at
			)
			VALUES (90003, 'RTMS', 'sample-rtms-20251220', '11680', '202512', 1, '{}', 'hash-3', 'NORMALIZED', now())
			""").update();
        jdbcClient.sql("""
			INSERT INTO trade (
			    id,
			    complex_id,
			    deal_date,
			    deal_amount,
			    floor,
			    excl_area,
			    apt_dong,
			    source,
			    source_key,
			    complex_pk,
			    apt_seq,
			    raw_ingest_id
			)
			VALUES (
			    9003,
			    502,
			    DATE '2025-12-20',
			    150000,
			    20,
			    114.93,
			    '201',
			    'RTMS',
			    'sample-rtms-20251220',
			    'COMPLEX-PK-502',
			    'APT-502',
			    90003
			)
			""").update();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.findParcelDetail(1001L, null)).hasValueSatisfying(detail -> {
            assertThat(detail.name()).isEqualTo("Sample Apartment");
            assertThat(detail.tradeName()).isEqualTo("Sample trade name");
        });
        assertThat(repository.findTradeList(1001L, null, 0, 25))
                .hasValueSatisfying(tradeList ->
                        assertThat(tradeList.trades()).extracting("tradeId").containsExactly(9003L, 9002L, 9001L));
    }

    @Test
    @DisplayName("detail은 재건축 필지에서 철거된 구단지 대신 생존(신축) 단지를 대표로 반환한다")
    void detailPrefersSurvivingComplexForRedevelopedParcel() {
        jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1001, 1, '1168010300101400009', 'Redeveloped lot', 37.5123, 127.0456)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt, use_date)
			VALUES
			    (501, 1001, 'COMPLEX-PK-501', 'APT-501', 'Old Mansion', NULL, 500, DATE '1985-01-01'),
			    (502, 1001, 'COMPLEX-PK-502', 'APT-502', 'New Tower', 'New Tower Trade', 900, DATE '2022-06-01')
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex_coordinate_case (parcel_id, pnu, status, relation_type, relation_confidence)
			VALUES (1001, '1168010300101400009', 'SKIPPED', 'REDEVELOPED', 'HIGH')
			""").update();
        jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    id, source, source_key, lawd_cd, deal_ymd, page_no, payload, payload_hash, status, processed_at
			)
			VALUES (90100, 'RTMS', 'redev-detail', '11680', '202501', 1, '{}', 'hash-redev-detail', 'NORMALIZED', now())
			""").update();
        jdbcClient.sql("""
			INSERT INTO trade (
			    id, complex_id, deal_date, deal_amount, floor, excl_area, apt_dong,
			    source, source_key, complex_pk, apt_seq, raw_ingest_id
			)
			VALUES
			    (9101, 501, DATE '2016-01-01', 80000, 5, 84.93, '101', 'RTMS', 'redev-d-501-1', 'COMPLEX-PK-501', 'APT-501', 90100),
			    (9102, 501, DATE '2018-01-01', 90000, 6, 84.93, '101', 'RTMS', 'redev-d-501-2', 'COMPLEX-PK-501', 'APT-501', 90100),
			    (9103, 502, DATE '2023-01-01', 180000, 15, 84.93, '101', 'RTMS', 'redev-d-502-1', 'COMPLEX-PK-502', 'APT-502', 90100),
			    (9104, 502, DATE '2025-01-01', 190000, 16, 84.93, '101', 'RTMS', 'redev-d-502-2', 'COMPLEX-PK-502', 'APT-502', 90100)
			""").update();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.findParcelDetail(1001L, null)).hasValueSatisfying(detail -> {
            assertThat(detail.name()).isEqualTo("New Tower");
            assertThat(detail.tradeName()).isEqualTo("New Tower Trade");
            assertThat(detail.unitCnt()).isEqualTo(900);
            assertThat(detail.useDate()).isEqualTo(LocalDate.of(2022, 6, 1));
        });
    }

    @Test
    @DisplayName("detail은 LOW confidence 재건축 필지를 확정 신축 대표로 승격하지 않는다")
    void detailDoesNotPromoteLowConfidenceRedevelopmentRepresentative() {
        jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1001, 1, '1168010300101400010', 'Low confidence redeveloped lot', 37.5123, 127.0456)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt, use_date)
			VALUES
			    (501, 1001, 'COMPLEX-PK-501', 'APT-501', 'Old Mansion', 'Old Trade', 500, DATE '1985-01-01'),
			    (502, 1001, 'COMPLEX-PK-502', 'APT-502', 'New Tower', 'New Tower Trade', 900, DATE '2022-06-01')
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex_coordinate_case (parcel_id, pnu, status, relation_type, relation_confidence)
			VALUES (1001, '1168010300101400010', 'SKIPPED', 'REDEVELOPED', 'LOW')
			""").update();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.findParcelDetail(1001L, null)).hasValueSatisfying(detail -> {
            assertThat(detail.complexId()).isEqualTo(501L);
            assertThat(detail.name()).isEqualTo("Old Mansion");
            assertThat(detail.tradeName()).isEqualTo("Old Trade");
        });
    }

    @Test
    @DisplayName("detail/trade read API는 parcel 또는 complex parent path가 없으면 empty가 된다")
    void missingParentPathReturnsEmpty() {
        seedPropertyExplorationData();
        ReadCapabilityReaders repository = new ReadCapabilityReaders(jdbcClient);

        assertThat(repository.findParcelDetail(404L, null)).isEmpty();
        assertThat(repository.findTradeList(404L, null, 0, 25)).isEmpty();
    }

    private void seedPublishedBuildingProfile() {
        jdbcClient.sql("""
            INSERT INTO building_register_collection_campaign(
              collection_id,mode,strategy,to_complex_id,status,purpose,target_scope,selection_seed,sample_size)
            VALUES ('10000000-0000-0000-0000-000000000001','profile','COMPARE_RECAP_TITLE',501,
              'CREATED','PROFILE_DISCOVERY','VALIDATION_SAMPLE','detail-profile-test',1)
            """).update();
        jdbcClient.sql("""
            INSERT INTO building_register_profile_parse_run(
              parse_run_id,source_collection_id,parser_version,status)
            VALUES ('10000000-0000-0000-0000-000000000002',
              '10000000-0000-0000-0000-000000000001','PROFILE_PUBLIC_TEST','RUNNING')
            """).update();
        jdbcClient.sql("""
            INSERT INTO building_register_profile_analysis_run(
              analysis_run_id,collection_id,parse_run_id,rules_version,status)
            VALUES ('10000000-0000-0000-0000-000000000003',
              '10000000-0000-0000-0000-000000000001',
              '10000000-0000-0000-0000-000000000002','PROFILE_PUBLIC_TEST','RUNNING')
            """).update();
        jdbcClient.sql("""
            INSERT INTO building_register_profile_projection_run(
              projection_run_id,analysis_run_id,collection_id,parse_run_id,
              projection_version,minimum_readiness,status)
            VALUES ('10000000-0000-0000-0000-000000000004',
              '10000000-0000-0000-0000-000000000003',
              '10000000-0000-0000-0000-000000000001',
              '10000000-0000-0000-0000-000000000002','PROFILE_PUBLIC_TEST',0.5,'RUNNING')
            """).update();
        jdbcClient.sql("""
            INSERT INTO building_register_profile_publication(
              publication_id,source_collection_id,source_parse_run_id,source_analysis_run_id,
              source_projection_run_id,rules_version,parser_version,status,
              expected_site_count,expected_building_count,expected_hierarchy_count,
              expected_evidence_count,expected_summary_count,site_count,building_count,
              hierarchy_count,evidence_count,summary_count,content_sha256,validated_at,published_at)
            VALUES (CAST(:publicationId AS uuid),'10000000-0000-0000-0000-000000000001',
              '10000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000003',
              '10000000-0000-0000-0000-000000000004','PROFILE_PUBLIC_TEST','PROFILE_PUBLIC_TEST','PUBLISHED',
              0,0,0,0,1,0,0,0,0,1,repeat('a',64),now(),now())
            """).param("publicationId", PROFILE_PUBLICATION_ID).update();
        jdbcClient.sql("""
            INSERT INTO complex_building_register_profile_summary(
              publication_id,complex_id,
              ratio_scope,ratio_quality,building_coverage_rate,floor_area_ratio,site_area_m2,
              building_area_m2,total_floor_area_m2,floor_area_ratio_area_m2,
              household_scope,household_quality,household_count,family_count,unit_count,
              parking_scope,parking_quality,total_parking_count,parking_per_household,
              indoor_mechanical_count,indoor_mechanical_area_m2,outdoor_mechanical_count,
              outdoor_mechanical_area_m2,indoor_automatic_count,indoor_automatic_area_m2,
              outdoor_automatic_count,outdoor_automatic_area_m2,
              building_scope,building_quality,main_building_count,attached_building_count,
              max_ground_floor_count,max_underground_floor_count,max_height_m,
              structure_names,roof_names,primary_use_names,
              elevator_scope,elevator_quality,ride_elevator_count,emergency_elevator_count,
              safety_scope,safety_quality,seismic_design_status,seismic_abilities,
              date_scope,date_quality,permit_date,construction_start_date,use_approval_date,
              address_scope,address_quality,parcel_address,road_address,
              energy_scope,energy_quality,energy_efficiency_grades,energy_saving_rate_min,
              energy_saving_rate_max,energy_epi_min,energy_epi_max,green_building_grades,
              green_cert_score_min,green_cert_score_max,intelligent_building_grades,
              intelligent_cert_score_min,intelligent_cert_score_max)
            VALUES (CAST(:publicationId AS uuid),501,
              'COMPLEX','VERIFIED',27.5,210.4,1000,275,2400,2104,
              'PARCEL','PNU_FALLBACK',740,12,760,
              'COMPLEX','VERIFIED',0,1.25,1,10.1,2,20.2,3,30.3,4,40.4,
              'COMPLEX','PARTIAL',8,2,25,3,82.4,
              ARRAY['철근콘크리트'],ARRAY['평지붕'],ARRAY['공동주택','근린생활시설'],
              'COMPLEX','VERIFIED',12,4,
              'COMPLEX','VERIFIED','ALL_APPLIED',ARRAY['VII-0.176g'],
              'COMPLEX','VERIFIED',DATE '2010-01-02',DATE '2011-03-04',DATE '2015-03-20',
              'PARCEL','PNU_FALLBACK','서울 표본구 1','서울 표본구 표본로 2',
              'COMPLEX','PARTIAL',ARRAY['1등급'],12.3,18.7,72.1,80.9,ARRAY['최우수'],
              85,90,ARRAY['1등급'],88,93)
            """).param("publicationId", PROFILE_PUBLICATION_ID).update();
    }

    private boolean extensionExists(String extensionName) {
        return Boolean.TRUE.equals(jdbcClient
                .sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM pg_extension
			    WHERE extname = :extensionName
			)
			""")
                .param("extensionName", extensionName)
                .query(Boolean.class)
                .single());
    }

    private String indexDefinition(String indexName) {
        return jdbcClient
                .sql("""
			SELECT indexdef
			FROM pg_indexes
			WHERE schemaname = 'public'
			  AND indexname = :indexName
			""")
                .param("indexName", indexName)
                .query(String.class)
                .optional()
                .orElse("");
    }

    private void seedSearchRankingData() {
        jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES
			    (3001, 1, '1168010300101400101', 'Quiet address 1', 37.5001, 127.0001),
			    (3002, 1, '1168010300101400102', 'Quiet address 2', 37.5002, 127.0002),
			    (3003, 1, '1168010300101400103', 'Quiet address 3', 37.5003, 127.0003),
			    (3004, 1, '1168010300101400104', 'River address only', 37.5004, 127.0004)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt)
			VALUES
			    (801, 3001, 'COMPLEX-PK-801', 'APT-801', 'River', NULL, 100),
			    (802, 3002, 'COMPLEX-PK-802', 'APT-802', 'River Heights', NULL, 200),
			    (803, 3003, 'COMPLEX-PK-803', 'APT-803', 'ZZZ Alias Display', NULL, 300),
			    (804, 3004, 'COMPLEX-PK-804', 'APT-804', 'AAA Address Only', NULL, 400)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex_name_alias (
			    complex_id,
			    alias_type,
			    alias_name,
			    normalized_name,
			    source
			)
			VALUES (
			    803,
			    'RTMS_APT_NAME',
			    'River Palace',
			    'riverpalace',
			    'RTMS'
			)
			""").update();
    }

    private void seedTwoComplexParcel() {
        jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (2001, 1, '1168010300101400099', 'Two complex address', 37.5123, 127.0456)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt)
			VALUES
			    (701, 2001, 'COMPLEX-PK-701', 'APT-701', 'Complex A', 'Complex A trade', 210),
			    (702, 2001, 'COMPLEX-PK-702', 'APT-702', 'Complex B', 'Complex B trade', 320)
			""").update();
        jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    id,
			    source,
			    source_key,
			    lawd_cd,
			    deal_ymd,
			    page_no,
			    payload,
			    payload_hash,
			    status,
			    processed_at
			)
			VALUES
			    (9701, 'RTMS', 'scoped-701', '11680', '202512', 1, '{}', 'hash-scoped-701', 'NORMALIZED', now()),
			    (9702, 'RTMS', 'scoped-702', '11680', '202512', 1, '{}', 'hash-scoped-702', 'NORMALIZED', now())
			""").update();
        jdbcClient.sql("""
			INSERT INTO trade (
			    id,
			    complex_id,
			    deal_date,
			    deal_amount,
			    floor,
			    excl_area,
			    apt_dong,
			    source,
			    source_key,
			    complex_pk,
			    apt_seq,
			    raw_ingest_id
			)
			VALUES
			    (9701, 701, DATE '2025-12-01', 125000, 12, 84.93, '101', 'RTMS', 'scoped-701', 'COMPLEX-PK-701', 'APT-701', 9701),
			    (9702, 702, DATE '2025-12-15', 130000, 15, 59.93, '201', 'RTMS', 'scoped-702', 'COMPLEX-PK-702', 'APT-702', 9702)
			""").update();
    }

    private static class ReadCapabilityReaders {

        private final JdbcComplexSearchReader searchReader;
        private final JdbcRegionNavigationReader regionReader;
        private final JdbcPropertyDetailReader detailReader;
        private final JdbcTradeHistoryReader tradeReader;

        private ReadCapabilityReaders(org.springframework.jdbc.core.simple.JdbcClient jdbcClient) {
            searchReader = new JdbcComplexSearchReader(jdbcClient);
            regionReader = new JdbcRegionNavigationReader(jdbcClient);
            detailReader = new JdbcPropertyDetailReader(jdbcClient);
            tradeReader = new JdbcTradeHistoryReader(jdbcClient);
        }

        private List<SearchComplexResult> searchComplexes(String query) {
            return searchReader.searchComplexes(query);
        }

        private List<ComplexSuggestionResult> suggestComplexes(String query, int limit) {
            return searchReader.suggestComplexes(query, limit);
        }

        private List<RegionSummaryResult> findRootRegions() {
            return regionReader.findRootRegions();
        }

        private Optional<RegionDetailResult> findRegionDetail(Long regionId) {
            return regionReader.findRegionDetail(regionId);
        }

        private Optional<List<ComplexSummaryResult>> findRegionComplexes(Long regionId, int limit, int offset) {
            return regionReader.findRegionComplexes(regionId, limit, offset);
        }

        private Optional<ParcelDetailResult> findParcelDetail(Long parcelId, Long complexId) {
            return detailReader.findParcelDetail(parcelId, complexId);
        }

        private Optional<List<ComplexSummaryResult>> findParcelComplexes(Long parcelId) {
            return detailReader.findParcelComplexes(parcelId);
        }

        private Optional<TradeListResult> findTradeList(Long parcelId, Long complexId, int page, int size) {
            return tradeReader.findTradeList(parcelId, complexId, page, size);
        }

        private Optional<List<TradeTrendPoint>> findTradeTrend(Long parcelId, Long complexId) {
            return tradeReader.findTradeTrend(parcelId, complexId);
        }

        private Optional<List<TradeTrendPoint>> findComplexTradeTrend(Long complexId) {
            return tradeReader.findComplexTradeTrend(complexId);
        }

        private Optional<TradeAreasResult> findTradeAreas(Long complexId) {
            return tradeReader.findTradeAreas(complexId);
        }

        private Optional<TradeListResult> findComplexTradeList(
                Long complexId, BigDecimal exclArea, int page, int size) {
            return tradeReader.findComplexTradeList(complexId, exclArea, page, size);
        }

        private Optional<TradeListResult> findComplexTradeList(Long complexId, int page, int size) {
            return tradeReader.findComplexTradeList(complexId, page, size);
        }

        private Optional<List<TradeTrendPoint>> findComplexTradeTrend(Long complexId, BigDecimal exclArea) {
            return tradeReader.findComplexTradeTrend(complexId, exclArea);
        }
    }
}
