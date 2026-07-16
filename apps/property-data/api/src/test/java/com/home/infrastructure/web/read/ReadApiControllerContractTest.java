package com.home.infrastructure.web.read;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.application.prediction.PricePredictionResult;
import com.home.application.prediction.PricePredictionUseCase;
import com.home.application.propertydetail.PropertyDetailService;
import com.home.application.read.ComplexSuggestionResult;
import com.home.application.read.ComplexSummaryResult;
import com.home.application.read.InvalidReadRequestException;
import com.home.application.read.ParcelDetailResult;
import com.home.application.read.RegionDetailResult;
import com.home.application.read.RegionSummaryResult;
import com.home.application.read.ResourceNotFoundException;
import com.home.application.read.SearchComplexResult;
import com.home.application.read.TradeListResult;
import com.home.application.read.TradeResult;
import com.home.application.read.TradeTrendPoint;
import com.home.application.regionnavigation.RegionNavigationService;
import com.home.application.search.ComplexSearchService;
import com.home.application.tradehistory.TradeHistoryService;
import com.home.domain.prediction.PredictionStatus;
import com.home.infrastructure.web.propertydetail.PropertyDetailController;
import com.home.infrastructure.web.regionnavigation.RegionNavigationController;
import com.home.infrastructure.web.search.SearchController;
import com.home.infrastructure.web.tradehistory.TradeHistoryController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({
    SearchController.class,
    RegionNavigationController.class,
    PropertyDetailController.class,
    TradeHistoryController.class
})
@ActiveProfiles("test")
class ReadApiControllerContractTest {

    private static final String OFFSET_TIMESTAMP_PATTERN =
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:Z|[+-]\\d{2}:\\d{2})$";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ComplexSearchService complexSearchService;

    @MockitoBean
    private RegionNavigationService regionNavigationService;

    @MockitoBean
    private PropertyDetailService propertyDetailService;

    @MockitoBean
    private TradeHistoryService tradeHistoryService;

    @MockitoBean
    private PricePredictionUseCase predictionUseCase;

    @Test
    @DisplayName("GET /api/v1/search/complexes는 canonical search field를 반환한다")
    void searchComplexesReturnsCanonicalFields() throws Exception {
        given(complexSearchService.searchComplexes(eq("Sample")))
                .willReturn(List.of(
                        new SearchComplexResult(501L, "Sample Apartment", 1001L, 37.5123, 127.0456, "Sample address")));

        mockMvc.perform(get("/api/v1/search/complexes").param("q", "  Sample  "))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].complexId").value(501))
                .andExpect(jsonPath("$[0].complexName").value("Sample Apartment"))
                .andExpect(jsonPath("$[0].parcelId").value(1001))
                .andExpect(jsonPath("$[0].latitude").value(37.5123))
                .andExpect(jsonPath("$[0].longitude").value(127.0456))
                .andExpect(jsonPath("$[0].address").value("Sample address"))
                .andExpect(jsonPath("$[0].complexPk").doesNotExist())
                .andExpect(jsonPath("$[0].aptSeq").doesNotExist())
                .andExpect(jsonPath("$[0].sourceKey").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/search/complexes는 blank search에서 empty array를 반환한다")
    void blankSearchReturnsEmptyArray() throws Exception {
        given(complexSearchService.searchComplexes(eq(""))).willReturn(List.of());

        mockMvc.perform(get("/api/v1/search/complexes").param("q", " "))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    @DisplayName("GET /api/v1/search/complexes는 100자·8 token 경계를 허용한다")
    void searchComplexesAllowsLengthAndTokenBoundaries() throws Exception {
        String maxLengthQuery = "가".repeat(100);
        String maxTokenQuery = "가 나 다 라 마 바 사 아";
        given(complexSearchService.searchComplexes(eq(maxLengthQuery))).willReturn(List.of());
        given(complexSearchService.searchComplexes(eq(maxTokenQuery))).willReturn(List.of());

        mockMvc.perform(get("/api/v1/search/complexes").param("q", maxLengthQuery))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        mockMvc.perform(get("/api/v1/search/complexes").param("q", maxTokenQuery))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    @DisplayName("GET /api/v1/search/complexes는 100자 또는 8 token 초과를 ProblemDetail 400으로 반환한다")
    void searchComplexesRejectsLengthAndTokenOverages() throws Exception {
        String overLengthQuery = "가".repeat(101);
        String overTokenQuery = "가 나 다 라 마 바 사 아 자";
        given(complexSearchService.searchComplexes(eq(overLengthQuery)))
                .willThrow(new InvalidReadRequestException("search query exceeds 100 characters"));
        given(complexSearchService.searchComplexes(eq(overTokenQuery)))
                .willThrow(new InvalidReadRequestException("search query exceeds 8 tokens"));

        for (String query : List.of(overLengthQuery, overTokenQuery)) {
            mockMvc.perform(get("/api/v1/search/complexes").param("q", query))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail").value("Invalid parameter format."));
        }
    }

    @Test
    @DisplayName("GET /api/v1/region은 root region을 반환한다")
    void rootRegionsReturnCanonicalFields() throws Exception {
        given(regionNavigationService.getRootRegions()).willReturn(List.of(new RegionSummaryResult(1L, "Seoul")));

        mockMvc.perform(get("/api/v1/region"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Seoul"));
    }

    @Test
    @DisplayName("GET /api/v1/region/{regionId}는 region detail과 child region을 반환한다")
    void regionDetailReturnsChildrenAndCenter() throws Exception {
        given(regionNavigationService.getRegionDetail(1L))
                .willReturn(new RegionDetailResult(
                        1L, "Seoul", 37.5663, 126.9780, List.of(new RegionSummaryResult(11L, "Gangnam-gu"))));

        mockMvc.perform(get("/api/v1/region/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Seoul"))
                .andExpect(jsonPath("$.latitude").value(37.5663))
                .andExpect(jsonPath("$.longitude").value(126.9780))
                .andExpect(jsonPath("$.children[0].id").value(11))
                .andExpect(jsonPath("$.children[0].name").value("Gangnam-gu"));
    }

    @Test
    @DisplayName("GET /api/v1/detail/{parcelId}는 parcel과 representative complex detail을 반환한다")
    void parcelDetailReturnsCanonicalFields() throws Exception {
        given(propertyDetailService.getParcelDetail(eq(1001L), isNull()))
                .willReturn(new ParcelDetailResult(
                        1001L,
                        501L,
                        37.5123,
                        127.0456,
                        "Sample address",
                        "Sample trade name",
                        "Sample Apartment",
                        8,
                        740,
                        new BigDecimal("12345.67"),
                        new BigDecimal("2345.67"),
                        new BigDecimal("98765.43"),
                        new BigDecimal("22.50"),
                        new BigDecimal("199.80"),
                        LocalDate.of(2015, 3, 20)));

        mockMvc.perform(get("/api/v1/detail/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelId").value(1001))
                .andExpect(jsonPath("$.complexId").value(501))
                .andExpect(jsonPath("$.latitude").value(37.5123))
                .andExpect(jsonPath("$.longitude").value(127.0456))
                .andExpect(jsonPath("$.address").value("Sample address"))
                .andExpect(jsonPath("$.displayName").value("Sample Apartment"))
                .andExpect(jsonPath("$.tradeName").value("Sample trade name"))
                .andExpect(jsonPath("$.name").value("Sample Apartment"))
                .andExpect(jsonPath("$.dongCnt").value(8))
                .andExpect(jsonPath("$.unitCnt").value(740))
                .andExpect(jsonPath("$.platArea").value(12345.67))
                .andExpect(jsonPath("$.archArea").value(2345.67))
                .andExpect(jsonPath("$.totArea").value(98765.43))
                .andExpect(jsonPath("$.bcRat").value(22.50))
                .andExpect(jsonPath("$.vlRat").value(199.80))
                .andExpect(jsonPath("$.useDate").value("2015-03-20"))
                .andExpect(jsonPath("$.complexPk").doesNotExist())
                .andExpect(jsonPath("$.aptSeq").doesNotExist())
                .andExpect(jsonPath("$.sourceKey").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/detail/{parcelId}는 compatible optional prediction READY field를 반환한다")
    void parcelDetailReturnsOptionalPredictionReadyField() throws Exception {
        given(propertyDetailService.getParcelDetail(eq(1001L), eq(501L)))
                .willReturn(new ParcelDetailResult(
                        1001L,
                        501L,
                        37.5123,
                        127.0456,
                        "Sample address",
                        "Sample trade name",
                        "Sample Apartment",
                        8,
                        740,
                        new BigDecimal("12345.67"),
                        new BigDecimal("2345.67"),
                        new BigDecimal("98765.43"),
                        new BigDecimal("22.50"),
                        new BigDecimal("199.80"),
                        LocalDate.of(2015, 3, 20)));
        given(predictionUseCase.getOrSchedulePrediction(501L))
                .willReturn(new PricePredictionResult(
                        PredictionStatus.READY,
                        "deployment__F37_monthly_anchor_prev3_rolling_huber_010",
                        179163L,
                        new BigDecimal("2115.5"),
                        new BigDecimal("6993.4"),
                        139425L,
                        218900L,
                        "recent_holdout_p95",
                        new BigDecimal("84.69"),
                        6,
                        9001L,
                        LocalDate.of(2026, 1, 1),
                        java.time.Instant.parse("2026-06-25T07:05:38Z"),
                        null));

        mockMvc.perform(get("/api/v1/detail/1001").param("complexId", "501"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelId").value(1001))
                .andExpect(jsonPath("$.complexId").value(501))
                .andExpect(jsonPath("$.prediction.status").value("READY"))
                .andExpect(jsonPath("$.prediction.modelVersion")
                        .value("deployment__F37_monthly_anchor_prev3_rolling_huber_010"))
                .andExpect(jsonPath("$.prediction.predictedDealAmount").value(179163))
                .andExpect(jsonPath("$.prediction.predictedPricePerM2").value(2115.5))
                .andExpect(jsonPath("$.prediction.predictedPricePerPyeong").value(6993.4))
                .andExpect(jsonPath("$.prediction.intervalLow").value(139425))
                .andExpect(jsonPath("$.prediction.intervalHigh").value(218900))
                .andExpect(jsonPath("$.prediction.intervalBasis").value("recent_holdout_p95"))
                .andExpect(jsonPath("$.prediction.targetAreaM2").value(84.69))
                .andExpect(jsonPath("$.prediction.targetFloor").value(6))
                .andExpect(jsonPath("$.prediction.basisTradeId").value(9001))
                .andExpect(jsonPath("$.prediction.basisDealDate").value("2026-01-01"))
                .andExpect(jsonPath("$.prediction.generatedAt").value("2026-06-25T07:05:38Z"))
                .andExpect(jsonPath("$.prediction.message").isEmpty())
                .andExpect(jsonPath("$.prediction.complexPk").doesNotExist())
                .andExpect(jsonPath("$.prediction.sourceKey").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/detail/{parcelId}는 prediction RuntimeException을 generic FAILED field로 격리한다")
    void parcelDetailDegradesPredictionRuntimeFailure() throws Exception {
        given(propertyDetailService.getParcelDetail(eq(1001L), isNull()))
                .willReturn(new ParcelDetailResult(
                        1001L,
                        501L,
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
                        LocalDate.of(2015, 3, 20)));
        given(predictionUseCase.getOrSchedulePrediction(501L))
                .willThrow(new IllegalStateException("sensitive provider failure"));

        mockMvc.perform(get("/api/v1/detail/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelId").value(1001))
                .andExpect(jsonPath("$.prediction.status").value("FAILED"))
                .andExpect(jsonPath("$.prediction.message").value("AI prediction unavailable"))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("sensitive provider failure"))));
    }

    @Test
    @DisplayName("GET /api/v1/detail/{parcelId}?complexId= 는 선택한 complex detail을 반환한다")
    void complexScopedParcelDetailReturnsSelectedComplex() throws Exception {
        given(propertyDetailService.getParcelDetail(1001L, 502L))
                .willReturn(new ParcelDetailResult(
                        1001L,
                        502L,
                        37.6123,
                        127.1456,
                        "Sample address",
                        "Tower B",
                        "Sample Tower B",
                        5,
                        320,
                        null,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.of(2020, 1, 1)));

        mockMvc.perform(get("/api/v1/detail/1001").param("complexId", "502"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelId").value(1001))
                .andExpect(jsonPath("$.complexId").value(502))
                .andExpect(jsonPath("$.latitude").value(37.6123))
                .andExpect(jsonPath("$.longitude").value(127.1456))
                .andExpect(jsonPath("$.name").value("Sample Tower B"))
                .andExpect(jsonPath("$.unitCnt").value(320));
    }

    @Test
    @DisplayName("GET /api/v1/trade/{parcelId}는 trade를 newest first로 반환한다")
    void tradeListReturnsCanonicalFields() throws Exception {
        given(tradeHistoryService.getTradeList(eq(1001L), isNull(), isNull(), isNull()))
                .willReturn(new TradeListResult(
                        1001L,
                        null,
                        List.of(
                                new TradeResult(
                                        9002L, LocalDate.of(2025, 12, 15), new BigDecimal("84.93"), 130000L, "101", 15),
                                new TradeResult(
                                        9001L,
                                        LocalDate.of(2025, 12, 1),
                                        new BigDecimal("84.93"),
                                        125000L,
                                        "101",
                                        12))));

        mockMvc.perform(get("/api/v1/trade/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelId").value(1001))
                .andExpect(jsonPath("$.complexId").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].tradeId").value(9002))
                .andExpect(jsonPath("$.content[0].dealDate").value("2025-12-15"))
                .andExpect(jsonPath("$.content[0].exclArea").value(84.93))
                .andExpect(jsonPath("$.content[0].dealAmount").value(130000))
                .andExpect(jsonPath("$.content[0].aptDong").value("101"))
                .andExpect(jsonPath("$.content[0].floor").value(15))
                .andExpect(jsonPath("$.content[0].complexPk").doesNotExist())
                .andExpect(jsonPath("$.content[0].aptSeq").doesNotExist())
                .andExpect(jsonPath("$.content[0].sourceKey").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/trade/{parcelId}?complexId= 는 선택한 complex trade만 반환한다")
    void complexScopedTradeListReturnsSelectedComplexTrades() throws Exception {
        given(tradeHistoryService.getTradeList(eq(1001L), eq(502L), isNull(), isNull()))
                .willReturn(new TradeListResult(
                        1001L,
                        502L,
                        List.of(new TradeResult(
                                9101L, LocalDate.of(2025, 12, 20), new BigDecimal("59.93"), 90000L, "201", 9))));

        mockMvc.perform(get("/api/v1/trade/1001").param("complexId", "502"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelId").value(1001))
                .andExpect(jsonPath("$.complexId").value(502))
                .andExpect(jsonPath("$.content[0].tradeId").value(9101))
                .andExpect(jsonPath("$.content[0].aptDong").value("201"));
    }

    @Test
    @DisplayName("GET /api/v1/trade/{parcelId}?page=&size= 는 use case에 page/size를 위임한다")
    void tradeListDelegatesPageAndSize() throws Exception {
        given(tradeHistoryService.getTradeList(eq(1001L), isNull(), eq(2), eq(5)))
                .willReturn(new TradeListResult(
                        1001L,
                        null,
                        List.of(new TradeResult(
                                9001L, LocalDate.of(2025, 12, 1), new BigDecimal("84.93"), 125000L, "101", 12)),
                        2,
                        5,
                        47));

        mockMvc.perform(get("/api/v1/trade/1001").param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(47))
                .andExpect(jsonPath("$.totalPages").value(10))
                .andExpect(jsonPath("$.content[0].tradeId").value(9001));
    }

    @Test
    @DisplayName("GET /api/v1/trade/{parcelId}는 valid parent의 empty page를 200으로 반환한다")
    void tradeListReturnsEmptyPageForExistingParent() throws Exception {
        given(tradeHistoryService.getTradeList(eq(1001L), isNull(), isNull(), isNull()))
                .willReturn(new TradeListResult(1001L, null, List.of(), 0, 25, 0));

        mockMvc.perform(get("/api/v1/trade/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/trade/{parcelId}는 malformed pagination을 canonical ProblemDetail 400으로 반환한다")
    void malformedTradePaginationReturnsProblemDetail400() throws Exception {
        mockMvc.perform(get("/api/v1/trade/1001").param("page", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid parameter format."))
                .andExpect(jsonPath("$.exception").value("MapApiException"))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(OFFSET_TIMESTAMP_PATTERN)));
    }

    @Test
    @DisplayName("GET /api/v1/detail/{parcelId}/complexes는 같은 parcel의 complex 목록을 반환한다")
    void parcelComplexesReturnSelectableComplexSummaries() throws Exception {
        given(propertyDetailService.getParcelComplexes(1001L))
                .willReturn(List.of(
                        new ComplexSummaryResult(
                                501L,
                                "Tower A",
                                1001L,
                                37.5123,
                                127.0456,
                                "Sample address",
                                5,
                                320,
                                LocalDate.of(2015, 3, 20)),
                        new ComplexSummaryResult(
                                502L,
                                "Tower B",
                                1001L,
                                37.6123,
                                127.1456,
                                "Sample address",
                                6,
                                410,
                                LocalDate.of(2020, 1, 1))));

        mockMvc.perform(get("/api/v1/detail/1001/complexes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].complexId").value(501))
                .andExpect(jsonPath("$[0].complexName").value("Tower A"))
                .andExpect(jsonPath("$[0].parcelId").value(1001))
                .andExpect(jsonPath("$[0].latitude").value(37.5123))
                .andExpect(jsonPath("$[0].longitude").value(127.0456))
                .andExpect(jsonPath("$[0].address").value("Sample address"))
                .andExpect(jsonPath("$[0].dongCnt").value(5))
                .andExpect(jsonPath("$[0].unitCnt").value(320))
                .andExpect(jsonPath("$[0].useDate").value("2015-03-20"))
                .andExpect(jsonPath("$[0].complexPk").doesNotExist())
                .andExpect(jsonPath("$[0].aptSeq").doesNotExist())
                .andExpect(jsonPath("$[0].sourceKey").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/complex/{complexId}는 complexId 단독 detail을 반환한다")
    void complexDetailByComplexIdReturnsCanonicalDetail() throws Exception {
        given(propertyDetailService.getComplexDetail(502L))
                .willReturn(new ParcelDetailResult(
                        1001L,
                        502L,
                        37.6123,
                        127.1456,
                        "Sample address",
                        "Tower B",
                        "Sample Tower B",
                        6,
                        410,
                        null,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.of(2020, 1, 1)));

        mockMvc.perform(get("/api/v1/complex/502"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelId").value(1001))
                .andExpect(jsonPath("$.complexId").value(502))
                .andExpect(jsonPath("$.latitude").value(37.6123))
                .andExpect(jsonPath("$.longitude").value(127.1456))
                .andExpect(jsonPath("$.address").value("Sample address"))
                .andExpect(jsonPath("$.tradeName").value("Tower B"))
                .andExpect(jsonPath("$.name").value("Sample Tower B"))
                .andExpect(jsonPath("$.unitCnt").value(410))
                .andExpect(jsonPath("$.complexPk").doesNotExist())
                .andExpect(jsonPath("$.aptSeq").doesNotExist())
                .andExpect(jsonPath("$.sourceKey").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/complex/{complexId}/trades는 complexId 단독 trade list를 반환한다")
    void complexTradeListByComplexIdReturnsCanonicalTrades() throws Exception {
        given(tradeHistoryService.getComplexTradeList(eq(502L), isNull(), isNull()))
                .willReturn(new TradeListResult(
                        1001L,
                        502L,
                        List.of(new TradeResult(
                                9101L, LocalDate.of(2025, 12, 20), new BigDecimal("59.93"), 90000L, "201", 9))));

        mockMvc.perform(get("/api/v1/complex/502/trades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelId").value(1001))
                .andExpect(jsonPath("$.complexId").value(502))
                .andExpect(jsonPath("$.content[0].tradeId").value(9101))
                .andExpect(jsonPath("$.content[0].dealAmount").value(90000))
                .andExpect(jsonPath("$.content[0].complexPk").doesNotExist())
                .andExpect(jsonPath("$.content[0].aptSeq").doesNotExist())
                .andExpect(jsonPath("$.content[0].sourceKey").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/trade/{parcelId}/trend는 월별 추세를 오름차순으로 반환한다")
    void tradeTrendReturnsMonthlySeries() throws Exception {
        given(tradeHistoryService.getTradeTrend(eq(1001L), isNull()))
                .willReturn(List.of(
                        new TradeTrendPoint("2025-10", 100000L, 1, 100000L, 100000L),
                        new TradeTrendPoint("2025-12", 127500L, 2, 125000L, 130000L)));

        mockMvc.perform(get("/api/v1/trade/1001/trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].month").value("2025-10"))
                .andExpect(jsonPath("$[1].month").value("2025-12"))
                .andExpect(jsonPath("$[1].avgAmount").value(127500))
                .andExpect(jsonPath("$[1].count").value(2))
                .andExpect(jsonPath("$[1].minAmount").value(125000))
                .andExpect(jsonPath("$[1].maxAmount").value(130000));
    }

    @Test
    @DisplayName("GET /api/v1/complex/{complexId}/trade-trend는 complexId 단독 월별 추세를 반환한다")
    void complexTradeTrendReturnsMonthlySeries() throws Exception {
        given(tradeHistoryService.getComplexTradeTrend(502L))
                .willReturn(List.of(new TradeTrendPoint("2025-12", 90000L, 1, 90000L, 90000L)));

        mockMvc.perform(get("/api/v1/complex/502/trade-trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].month").value("2025-12"))
                .andExpect(jsonPath("$[0].avgAmount").value(90000))
                .andExpect(jsonPath("$[0].count").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/search/complexes/suggestions는 autocomplete field를 반환한다")
    void complexSuggestionsReturnAutocompleteFields() throws Exception {
        given(complexSearchService.suggestComplexes("Sample"))
                .willReturn(List.of(new ComplexSuggestionResult(501L, "Sample Apartment", 1001L, "Sample address")));

        mockMvc.perform(get("/api/v1/search/complexes/suggestions").param("q", "  Sample  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].complexId").value(501))
                .andExpect(jsonPath("$[0].complexName").value("Sample Apartment"))
                .andExpect(jsonPath("$[0].parcelId").value(1001))
                .andExpect(jsonPath("$[0].address").value("Sample address"))
                .andExpect(jsonPath("$[0].complexPk").doesNotExist())
                .andExpect(jsonPath("$[0].aptSeq").doesNotExist())
                .andExpect(jsonPath("$[0].sourceKey").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/search/complexes/suggestions는 blank query에서 empty array를 반환한다")
    void blankComplexSuggestionsReturnEmptyArray() throws Exception {
        given(complexSearchService.suggestComplexes("")).willReturn(List.of());

        mockMvc.perform(get("/api/v1/search/complexes/suggestions").param("q", " "))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    @DisplayName("GET /api/v1/region/{regionId}/complexes는 region complex page를 반환한다")
    void regionComplexesReturnPagedComplexSummaries() throws Exception {
        given(regionNavigationService.getRegionComplexes(11L, 25, 50))
                .willReturn(List.of(new ComplexSummaryResult(
                        701L,
                        "Region Complex",
                        2001L,
                        37.5123,
                        127.0456,
                        "Region address",
                        8,
                        740,
                        LocalDate.of(2018, 5, 1))));

        mockMvc.perform(get("/api/v1/region/11/complexes").param("limit", "25").param("offset", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].complexId").value(701))
                .andExpect(jsonPath("$[0].complexName").value("Region Complex"))
                .andExpect(jsonPath("$[0].parcelId").value(2001))
                .andExpect(jsonPath("$[0].latitude").value(37.5123))
                .andExpect(jsonPath("$[0].longitude").value(127.0456))
                .andExpect(jsonPath("$[0].address").value("Region address"))
                .andExpect(jsonPath("$[0].unitCnt").value(740));
    }

    @Test
    @DisplayName("GET /api/v1/region/{regionId}/complexes는 valid parent의 empty result를 200으로 반환한다")
    void regionComplexesReturnEmptyArrayForExistingParent() throws Exception {
        given(regionNavigationService.getRegionComplexes(11L, null, null)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/region/11/complexes"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    @DisplayName("GET /api/v1/region/{regionId}/complexes는 invalid page request를 ProblemDetail 400으로 반환한다")
    void invalidRegionComplexPageReturnsProblemDetail400() throws Exception {
        given(regionNavigationService.getRegionComplexes(11L, 0, 0))
                .willThrow(new InvalidReadRequestException("limit must be greater than 0"));

        mockMvc.perform(get("/api/v1/region/11/complexes").param("limit", "0").param("offset", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid parameter format."))
                .andExpect(jsonPath("$.exception").value("MapApiException"));
    }

    @Test
    @DisplayName("GET read endpoint는 non-positive resource id를 service 호출 전에 거부한다")
    void nonPositiveReadResourceIdReturnsProblemDetail400BeforeServiceCall() throws Exception {
        mockMvc.perform(get("/api/v1/detail/0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid parameter format."))
                .andExpect(jsonPath("$.exception").value("MapApiException"));

        verifyNoInteractions(propertyDetailService, predictionUseCase);
    }

    @Test
    @DisplayName("GET read endpoint는 missing parent에서 ProblemDetail 404를 반환한다")
    void missingReadResourceReturnsProblemDetail404() throws Exception {
        given(regionNavigationService.getRegionDetail(404L))
                .willThrow(new ResourceNotFoundException("region not found"));

        mockMvc.perform(get("/api/v1/region/404"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Resource not found."))
                .andExpect(jsonPath("$.exception").value("ResourceNotFoundException"))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(OFFSET_TIMESTAMP_PATTERN)));
    }
}
