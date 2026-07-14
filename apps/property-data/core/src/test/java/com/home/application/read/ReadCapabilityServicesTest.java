package com.home.application.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.propertydetail.PropertyDetailReader;
import com.home.application.propertydetail.PropertyDetailService;
import com.home.application.regionnavigation.RegionNavigationReader;
import com.home.application.regionnavigation.RegionNavigationService;
import com.home.application.search.ComplexSearchReader;
import com.home.application.search.ComplexSearchService;
import com.home.application.tradehistory.TradeHistoryReader;
import com.home.application.tradehistory.TradeHistoryService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class ReadCapabilityServicesTest {

    @Test
    @DisplayName("region/trade composite read method는 read-only repeatable-read transaction 경계를 선언한다")
    void compositeReadsDeclareRepeatableReadTransactions() throws Exception {
        assertRepeatableRead(RegionNavigationService.class, "getRegionDetail", Long.class);
        assertRepeatableRead(
                RegionNavigationService.class, "getRegionComplexes", Long.class, Integer.class, Integer.class);
        assertRepeatableRead(
                TradeHistoryService.class, "getTradeList", Long.class, Long.class, Integer.class, Integer.class);
        assertRepeatableRead(
                TradeHistoryService.class, "getComplexTradeList", Long.class, Integer.class, Integer.class);
        assertRepeatableRead(TradeHistoryService.class, "getTradeTrend", Long.class, Long.class);
        assertRepeatableRead(TradeHistoryService.class, "getComplexTradeTrend", Long.class);
        assertThat(RegionNavigationService.class.getMethod("getRootRegions").getAnnotation(Transactional.class))
                .isNull();
    }

    @Test
    @DisplayName("search service는 query를 정규화하고 suggestion limit을 소유한다")
    void normalizesSearchQueriesAndOwnsSuggestionLimit() {
        CapturingReaders readers = new CapturingReaders();
        ComplexSearchService service = new ComplexSearchService(readers);

        assertThat(service.searchComplexes("  대림   대림 DAELIM daelim  "))
                .singleElement()
                .extracting(SearchComplexResult::complexName)
                .isEqualTo("Sample Apartment");
        assertThat(readers.searchQuery).isEqualTo("대림 DAELIM");

        assertThat(service.suggestComplexes("  Sample  "))
                .singleElement()
                .extracting(ComplexSuggestionResult::complexName)
                .isEqualTo("Sample Apartment");
        assertThat(readers.suggestionQuery).isEqualTo("Sample");
        assertThat(readers.suggestionLimit).isEqualTo(8);
    }

    @Test
    @DisplayName("search service는 blank input을 조회하지 않고 query 경계를 검증한다")
    void validatesSearchQueryBeforeReaderAccess() {
        CapturingReaders readers = new CapturingReaders();
        ComplexSearchService service = new ComplexSearchService(readers);

        assertThat(service.searchComplexes(" ")).isEmpty();
        assertThat(service.searchComplexes(null)).isEmpty();
        assertThat(service.suggestComplexes(" ")).isEmpty();
        assertThat(service.suggestComplexes(null)).isEmpty();
        assertThat(service.searchComplexes("가".repeat(100))).hasSize(1);
        assertThat(service.searchComplexes("가 나 다 라 마 바 사 아")).hasSize(1);

        assertThatThrownBy(() -> service.searchComplexes("가".repeat(101)))
                .isInstanceOf(InvalidReadRequestException.class);
        assertThatThrownBy(() -> service.searchComplexes("가 나 다 라 마 바 사 아 자"))
                .isInstanceOf(InvalidReadRequestException.class);
    }

    @Test
    @DisplayName("region navigation service는 조회와 limit/offset 정책을 소유한다")
    void delegatesRegionNavigationAndValidatesPaging() {
        CapturingReaders readers = new CapturingReaders();
        RegionNavigationService service = new RegionNavigationService(readers);

        assertThat(service.getRootRegions()).containsExactly(new RegionSummaryResult(1L, "Seoul"));
        assertThat(service.getRegionDetail(1L).name()).isEqualTo("Seoul");
        assertThat(service.getRegionComplexes(1L, 500, 2))
                .singleElement()
                .extracting(ComplexSummaryResult::complexId)
                .isEqualTo(501L);
        assertThat(readers.regionComplexLimit).isEqualTo(100);
        assertThat(readers.regionComplexOffset).isEqualTo(2);

        service.getRegionComplexes(1L, null, null);
        assertThat(readers.regionComplexLimit).isEqualTo(50);
        assertThat(readers.regionComplexOffset).isZero();
        assertThatThrownBy(() -> service.getRegionComplexes(1L, 0, 0))
                .isInstanceOf(InvalidReadRequestException.class)
                .hasMessageContaining("limit must be greater than 0");
        assertThatThrownBy(() -> service.getRegionComplexes(1L, 10, -1))
                .isInstanceOf(InvalidReadRequestException.class)
                .hasMessageContaining("offset must be greater than or equal to 0");
        assertThatThrownBy(() -> service.getRegionDetail(404L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("region not found");
        assertThatThrownBy(() -> service.getRegionComplexes(404L, 10, 0))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("region not found");
    }

    @Test
    @DisplayName("property detail service는 parcel과 complex detail의 404 의미를 보존한다")
    void delegatesPropertyDetailsAndPreservesMissingParentMeaning() {
        PropertyDetailService service = new PropertyDetailService(new CapturingReaders());

        assertThat(service.getParcelDetail(1001L, 501L).complexId()).isEqualTo(501L);
        assertThat(service.getParcelComplexes(1001L))
                .singleElement()
                .extracting(ComplexSummaryResult::complexName)
                .isEqualTo("Sample Apartment");
        assertThat(service.getComplexDetail(501L).complexId()).isEqualTo(501L);

        assertThatThrownBy(() -> service.getParcelDetail(404L, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("parcel detail not found");
        assertThatThrownBy(() -> service.getParcelComplexes(404L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("parcel not found");
        assertThatThrownBy(() -> service.getComplexDetail(404L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("complex detail not found");
    }

    @Test
    @DisplayName("trade history service는 명시적 page/size와 trend 조회를 소유한다")
    void delegatesTradeHistoryAndValidatesPaging() {
        CapturingReaders readers = new CapturingReaders();
        TradeHistoryService service = new TradeHistoryService(readers);

        assertThat(service.getTradeList(1001L, 501L, null, null).complexId()).isEqualTo(501L);
        assertThat(readers.tradePage).isZero();
        assertThat(readers.tradeSize).isEqualTo(25);
        assertThat(service.getComplexTradeList(501L, 2, 500).complexId()).isEqualTo(501L);
        assertThat(readers.tradePage).isEqualTo(2);
        assertThat(readers.tradeSize).isEqualTo(100);
        assertThat(service.getTradeTrend(1001L, 501L))
                .singleElement()
                .extracting(TradeTrendPoint::month)
                .isEqualTo("2024-11");
        assertThat(service.getComplexTradeTrend(501L)).hasSize(1);

        assertThatThrownBy(() -> service.getTradeList(1001L, null, -1, 10))
                .isInstanceOf(InvalidReadRequestException.class)
                .hasMessageContaining("page must be greater than or equal to 0");
        assertThatThrownBy(() -> service.getTradeList(1001L, null, 0, 0))
                .isInstanceOf(InvalidReadRequestException.class)
                .hasMessageContaining("size must be greater than 0");
        assertThatThrownBy(() -> service.getComplexTradeList(501L, -1, 10))
                .isInstanceOf(InvalidReadRequestException.class);
        assertThatThrownBy(() -> service.getTradeList(404L, null, 0, 25))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("parcel trade parent not found");
        assertThatThrownBy(() -> service.getComplexTradeList(404L, 0, 25))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("complex trade parent not found");
        assertThatThrownBy(() -> service.getTradeTrend(404L, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("parcel trade parent not found");
        assertThatThrownBy(() -> service.getComplexTradeTrend(404L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("complex trade parent not found");
    }

    private static class CapturingReaders
            implements ComplexSearchReader, RegionNavigationReader, PropertyDetailReader, TradeHistoryReader {

        private String searchQuery;
        private String suggestionQuery;
        private int suggestionLimit;
        private int regionComplexLimit;
        private int regionComplexOffset;
        private int tradePage;
        private int tradeSize;

        @Override
        public List<SearchComplexResult> searchComplexes(String query) {
            searchQuery = query;
            return List.of(
                    new SearchComplexResult(501L, "Sample Apartment", 1001L, 37.5123, 127.0456, "Sample address"));
        }

        @Override
        public List<ComplexSuggestionResult> suggestComplexes(String query, int limit) {
            suggestionQuery = query;
            suggestionLimit = limit;
            return List.of(new ComplexSuggestionResult(501L, "Sample Apartment", 1001L, "Sample address"));
        }

        @Override
        public List<RegionSummaryResult> findRootRegions() {
            return List.of(new RegionSummaryResult(1L, "Seoul"));
        }

        @Override
        public Optional<RegionDetailResult> findRegionDetail(Long regionId) {
            return Long.valueOf(404L).equals(regionId)
                    ? Optional.empty()
                    : Optional.of(new RegionDetailResult(1L, "Seoul", 37.5663, 126.9780, List.of()));
        }

        @Override
        public Optional<List<ComplexSummaryResult>> findRegionComplexes(Long regionId, int limit, int offset) {
            regionComplexLimit = limit;
            regionComplexOffset = offset;
            return Long.valueOf(404L).equals(regionId) ? Optional.empty() : Optional.of(List.of(summary()));
        }

        @Override
        public Optional<ParcelDetailResult> findParcelDetail(Long parcelId, Long complexId) {
            return Long.valueOf(404L).equals(parcelId) ? Optional.empty() : Optional.of(detail(complexId));
        }

        @Override
        public Optional<List<ComplexSummaryResult>> findParcelComplexes(Long parcelId) {
            return Long.valueOf(404L).equals(parcelId) ? Optional.empty() : Optional.of(List.of(summary()));
        }

        @Override
        public Optional<ParcelDetailResult> findComplexDetail(Long complexId) {
            return Long.valueOf(404L).equals(complexId) ? Optional.empty() : Optional.of(detail(complexId));
        }

        @Override
        public Optional<TradeListResult> findTradeList(Long parcelId, Long complexId, int page, int size) {
            tradePage = page;
            tradeSize = size;
            return Long.valueOf(404L).equals(parcelId)
                    ? Optional.empty()
                    : Optional.of(new TradeListResult(parcelId, complexId, List.of(), page, size, 0));
        }

        @Override
        public Optional<TradeListResult> findComplexTradeList(Long complexId, int page, int size) {
            tradePage = page;
            tradeSize = size;
            return Long.valueOf(404L).equals(complexId)
                    ? Optional.empty()
                    : Optional.of(new TradeListResult(1001L, complexId, List.of(), page, size, 0));
        }

        @Override
        public Optional<List<TradeTrendPoint>> findTradeTrend(Long parcelId, Long complexId) {
            return Long.valueOf(404L).equals(parcelId) ? Optional.empty() : Optional.of(List.of(trend()));
        }

        @Override
        public Optional<List<TradeTrendPoint>> findComplexTradeTrend(Long complexId) {
            return Long.valueOf(404L).equals(complexId) ? Optional.empty() : Optional.of(List.of(trend()));
        }

        private ParcelDetailResult detail(Long complexId) {
            return new ParcelDetailResult(
                    1001L,
                    complexId,
                    37.5123,
                    127.0456,
                    "Sample address",
                    "Sample trade name",
                    "Sample Apartment",
                    8,
                    740,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        private ComplexSummaryResult summary() {
            return new ComplexSummaryResult(
                    501L, "Sample Apartment", 1001L, 37.5123, 127.0456, "Sample address", 8, 740, null);
        }

        private TradeTrendPoint trend() {
            return new TradeTrendPoint("2024-11", 89500L, 3, 80000L, 92000L);
        }
    }

    private void assertRepeatableRead(Class<?> type, String methodName, Class<?>... parameterTypes) throws Exception {
        Transactional transaction = type.getMethod(methodName, parameterTypes).getAnnotation(Transactional.class);
        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }
}
