package com.home.infrastructure.web.read;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static com.epages.restdocs.apispec.ResourceSnippetParameters.builder;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.application.prediction.PricePredictionUseCase;
import com.home.application.propertydetail.PropertyDetailService;
import com.home.application.read.ComplexSuggestionResult;
import com.home.application.read.ComplexSummaryResult;
import com.home.application.read.ParcelDetailResult;
import com.home.application.read.RegionDetailResult;
import com.home.application.read.RegionSummaryResult;
import com.home.application.read.SearchComplexResult;
import com.home.application.read.TradeListResult;
import com.home.application.read.TradeResult;
import com.home.application.read.TradeTrendPoint;
import com.home.application.regionnavigation.RegionNavigationService;
import com.home.application.search.ComplexSearchService;
import com.home.application.tradehistory.TradeHistoryService;
import com.home.infrastructure.web.propertydetail.PropertyDetailController;
import com.home.infrastructure.web.regionnavigation.RegionNavigationController;
import com.home.infrastructure.web.search.SearchController;
import com.home.infrastructure.web.tradehistory.TradeHistoryController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@Tag("restDocs")
@WebMvcTest({
    SearchController.class,
    RegionNavigationController.class,
    PropertyDetailController.class,
    TradeHistoryController.class
})
@AutoConfigureRestDocs
@ActiveProfiles("test")
class ReadApiRestDocsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ComplexSearchService complexSearchService;

    @MockitoBean
    private RegionNavigationService regionNavigationService;

    @MockitoBean
    private PropertyDetailService propertyDetailService;

    @MockitoBean
    private PricePredictionUseCase predictionUseCase;

    @MockitoBean
    private TradeHistoryService tradeHistoryService;

    @Test
    @DisplayName("GET /api/v1/search/complexes REST Docs를 생성한다")
    void documentSearchComplexes() throws Exception {
        given(complexSearchService.searchComplexes(eq("Sample")))
                .willReturn(List.of(
                        new SearchComplexResult(501L, "Sample Apartment", 1001L, 37.5123, 127.0456, "Sample address")));

        mockMvc.perform(get("/api/v1/search/complexes").param("q", "Sample"))
                .andExpect(status().isOk())
                .andDo(document(
                        "read-search-complexes-success",
                        queryParameters(
                                parameterWithName("q")
                                        .description(
                                                "Trimmed complex search query; maximum 100 characters and 8 unique whitespace-separated tokens.")),
                        responseFields(
                                fieldWithPath("[].complexId")
                                        .type(JsonFieldType.NUMBER)
                                        .description("Complex id."),
                                fieldWithPath("[].complexName")
                                        .type(JsonFieldType.STRING)
                                        .description("Complex display name."),
                                fieldWithPath("[].parcelId")
                                        .type(JsonFieldType.NUMBER)
                                        .description("Parcel id used by map/detail APIs."),
                                fieldWithPath("[].latitude")
                                        .type(JsonFieldType.NUMBER)
                                        .description("Complex marker latitude."),
                                fieldWithPath("[].longitude")
                                        .type(JsonFieldType.NUMBER)
                                        .description("Complex marker longitude."),
                                fieldWithPath("[].address")
                                        .type(JsonFieldType.STRING)
                                        .optional()
                                        .description("Parcel address.")),
                        resource(builder()
                                .tag("Read")
                                .summary("Search complexes")
                                .description("Searches apartment complexes by user-entered text.")
                                .queryParameters(
                                        parameterWithName("q")
                                                .description(
                                                        "Trimmed complex search query; maximum 100 characters and 8 unique whitespace-separated tokens."))
                                .responseFields(
                                        fieldWithPath("[].complexId")
                                                .type(JsonFieldType.NUMBER)
                                                .description("Complex id."),
                                        fieldWithPath("[].complexName")
                                                .type(JsonFieldType.STRING)
                                                .description("Complex display name."),
                                        fieldWithPath("[].parcelId")
                                                .type(JsonFieldType.NUMBER)
                                                .description("Parcel id."),
                                        fieldWithPath("[].latitude")
                                                .type(JsonFieldType.NUMBER)
                                                .description("Latitude."),
                                        fieldWithPath("[].longitude")
                                                .type(JsonFieldType.NUMBER)
                                                .description("Longitude."),
                                        fieldWithPath("[].address")
                                                .type(JsonFieldType.STRING)
                                                .optional()
                                                .description("Address."))
                                .build())));
    }

    @Test
    @DisplayName("GET /api/v1/search/complexes/suggestions REST Docs를 생성한다")
    void documentComplexSuggestions() throws Exception {
        given(complexSearchService.suggestComplexes(eq("Sample")))
                .willReturn(List.of(new ComplexSuggestionResult(501L, "Sample Apartment", 1001L, "Sample address")));

        mockMvc.perform(get("/api/v1/search/complexes/suggestions").param("q", "Sample"))
                .andExpect(status().isOk())
                .andDo(document(
                        "read-complex-suggestions-success",
                        queryParameters(
                                parameterWithName("q")
                                        .description(
                                                "Trimmed complex suggestion query; maximum 100 characters and 8 unique whitespace-separated tokens.")),
                        responseFields(
                                fieldWithPath("[].complexId")
                                        .type(JsonFieldType.NUMBER)
                                        .description("Complex id."),
                                fieldWithPath("[].complexName")
                                        .type(JsonFieldType.STRING)
                                        .description("Complex display name."),
                                fieldWithPath("[].parcelId")
                                        .type(JsonFieldType.NUMBER)
                                        .description("Parcel id."),
                                fieldWithPath("[].address")
                                        .type(JsonFieldType.STRING)
                                        .optional()
                                        .description("Parcel address.")),
                        resource(builder()
                                .tag("Read")
                                .summary("Suggest complexes")
                                .description("Returns lightweight complex suggestions for autocomplete.")
                                .queryParameters(
                                        parameterWithName("q")
                                                .description(
                                                        "Trimmed complex suggestion query; maximum 100 characters and 8 unique whitespace-separated tokens."))
                                .responseFields(
                                        fieldWithPath("[].complexId")
                                                .type(JsonFieldType.NUMBER)
                                                .description("Complex id."),
                                        fieldWithPath("[].complexName")
                                                .type(JsonFieldType.STRING)
                                                .description("Complex display name."),
                                        fieldWithPath("[].parcelId")
                                                .type(JsonFieldType.NUMBER)
                                                .description("Parcel id."),
                                        fieldWithPath("[].address")
                                                .type(JsonFieldType.STRING)
                                                .optional()
                                                .description("Address."))
                                .build())));
    }

    @Test
    @DisplayName("GET /api/v1/region과 GET /api/v1/region/{regionId} REST Docs를 생성한다")
    void documentRegionNavigation() throws Exception {
        given(regionNavigationService.getRootRegions()).willReturn(List.of(new RegionSummaryResult(1L, "Seoul", "11")));
        given(regionNavigationService.getRegionDetail(1L))
                .willReturn(new RegionDetailResult(
                        1L,
                        "Seoul",
                        "11",
                        37.5663,
                        126.9780,
                        List.of(new RegionSummaryResult(11L, "Gangnam-gu", "11680"))));

        mockMvc.perform(get("/api/v1/region"))
                .andExpect(status().isOk())
                .andDo(document(
                        "read-root-regions-success",
                        responseFields(
                                fieldWithPath("[].id")
                                        .type(JsonFieldType.NUMBER)
                                        .description("Root region id."),
                                fieldWithPath("[].name")
                                        .type(JsonFieldType.STRING)
                                        .description("Root region name."),
                                fieldWithPath("[].code")
                                        .type(JsonFieldType.STRING)
                                        .description("Root SIDO code.")),
                        resource(builder()
                                .tag("Read")
                                .summary("Get root regions")
                                .description("Returns root regions for region navigation.")
                                .responseFields(
                                        fieldWithPath("[].id")
                                                .type(JsonFieldType.NUMBER)
                                                .description("Root region id."),
                                        fieldWithPath("[].name")
                                                .type(JsonFieldType.STRING)
                                                .description("Root region name."),
                                        fieldWithPath("[].code")
                                                .type(JsonFieldType.STRING)
                                                .description("Root SIDO code."))
                                .build())));

        mockMvc.perform(get("/api/v1/region/{regionId}", 1L))
                .andExpect(status().isOk())
                .andDo(document(
                        "read-region-detail-success",
                        pathParameters(parameterWithName("regionId").description("Region id.")),
                        responseFields(
                                fieldWithPath("id").type(JsonFieldType.NUMBER).description("Region id."),
                                fieldWithPath("name").type(JsonFieldType.STRING).description("Region name."),
                                fieldWithPath("code").type(JsonFieldType.STRING).description("Region code."),
                                fieldWithPath("latitude")
                                        .type(JsonFieldType.NUMBER)
                                        .optional()
                                        .description("Region center latitude."),
                                fieldWithPath("longitude")
                                        .type(JsonFieldType.NUMBER)
                                        .optional()
                                        .description("Region center longitude."),
                                fieldWithPath("children")
                                        .type(JsonFieldType.ARRAY)
                                        .description("Child regions."),
                                fieldWithPath("children[].id")
                                        .type(JsonFieldType.NUMBER)
                                        .description("Child region id."),
                                fieldWithPath("children[].name")
                                        .type(JsonFieldType.STRING)
                                        .description("Child region name."),
                                fieldWithPath("children[].code")
                                        .type(JsonFieldType.STRING)
                                        .description("Child region code.")),
                        resource(builder()
                                .tag("Read")
                                .summary("Get region detail")
                                .description("Returns one region, its children, and center coordinates.")
                                .pathParameters(parameterWithName("regionId").description("Region id."))
                                .responseFields(
                                        fieldWithPath("id")
                                                .type(JsonFieldType.NUMBER)
                                                .description("Region id."),
                                        fieldWithPath("name")
                                                .type(JsonFieldType.STRING)
                                                .description("Region name."),
                                        fieldWithPath("code")
                                                .type(JsonFieldType.STRING)
                                                .description("Region code."),
                                        fieldWithPath("latitude")
                                                .type(JsonFieldType.NUMBER)
                                                .optional()
                                                .description("Latitude."),
                                        fieldWithPath("longitude")
                                                .type(JsonFieldType.NUMBER)
                                                .optional()
                                                .description("Longitude."),
                                        fieldWithPath("children")
                                                .type(JsonFieldType.ARRAY)
                                                .description("Child regions."),
                                        fieldWithPath("children[].id")
                                                .type(JsonFieldType.NUMBER)
                                                .description("Child region id."),
                                        fieldWithPath("children[].name")
                                                .type(JsonFieldType.STRING)
                                                .description("Child region name."),
                                        fieldWithPath("children[].code")
                                                .type(JsonFieldType.STRING)
                                                .description("Child region code."))
                                .build())));
    }

    @Test
    @DisplayName("GET /api/v1/region/{regionId}/complexes REST Docs를 생성한다")
    void documentRegionComplexes() throws Exception {
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

        mockMvc.perform(get("/api/v1/region/{regionId}/complexes", 11L)
                        .param("limit", "25")
                        .param("offset", "50"))
                .andExpect(status().isOk())
                .andDo(document(
                        "read-region-complexes-success",
                        pathParameters(parameterWithName("regionId").description("Region id.")),
                        queryParameters(
                                parameterWithName("limit").optional().description("Optional page size."),
                                parameterWithName("offset").optional().description("Optional zero-based row offset.")),
                        responseFields(complexSummaryFields()),
                        resource(builder()
                                .tag("Read")
                                .summary("Get region complexes")
                                .description("Returns a paged list of complexes under one region and its children.")
                                .pathParameters(parameterWithName("regionId").description("Region id."))
                                .queryParameters(
                                        parameterWithName("limit").optional().description("Optional page size."),
                                        parameterWithName("offset")
                                                .optional()
                                                .description("Optional zero-based row offset."))
                                .responseFields(complexSummaryFields())
                                .build())));
    }

    @Test
    @DisplayName("GET /api/v1/detail/{parcelId}와 GET /api/v1/trade/{parcelId} REST Docs를 생성한다")
    void documentDetailAndTrade() throws Exception {
        given(propertyDetailService.getParcelDetail(1001L, 501L))
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
        given(tradeHistoryService.getTradeList(1001L, 501L, null, null))
                .willReturn(new TradeListResult(
                        1001L,
                        501L,
                        List.of(new TradeResult(
                                9002L, LocalDate.of(2025, 12, 15), new BigDecimal("84.93"), 130000L, "101", 15))));

        mockMvc.perform(get("/api/v1/detail/{parcelId}", 1001L).param("complexId", "501"))
                .andExpect(status().isOk())
                .andDo(document(
                        "read-detail-success",
                        pathParameters(parameterWithName("parcelId").description("Parcel id.")),
                        queryParameters(
                                parameterWithName("complexId").optional().description("Optional selected complex id.")),
                        responseFields(detailFields()),
                        resource(builder()
                                .tag("Read")
                                .summary("Get parcel detail")
                                .description("Returns parcel and selected or representative complex details.")
                                .pathParameters(parameterWithName("parcelId").description("Parcel id."))
                                .queryParameters(parameterWithName("complexId")
                                        .optional()
                                        .description("Optional selected complex id."))
                                .responseFields(detailFields())
                                .build())));

        mockMvc.perform(get("/api/v1/trade/{parcelId}", 1001L).param("complexId", "501"))
                .andExpect(status().isOk())
                .andDo(document(
                        "read-trade-success",
                        pathParameters(parameterWithName("parcelId").description("Parcel id.")),
                        queryParameters(parcelTradeQueryParameters()),
                        responseFields(tradeListFields()),
                        resource(builder()
                                .tag("Read")
                                .summary("Get parcel trades")
                                .description(
                                        "Returns trades newest first for selected complex or complexes under a parcel.")
                                .pathParameters(parameterWithName("parcelId").description("Parcel id."))
                                .queryParameters(parcelTradeQueryParameters())
                                .responseFields(tradeListFields())
                                .build())));
    }

    @Test
    @DisplayName("GET /api/v1/detail/{parcelId}/complexes REST Docs를 생성한다")
    void documentParcelComplexes() throws Exception {
        given(propertyDetailService.getParcelComplexes(1001L))
                .willReturn(List.of(new ComplexSummaryResult(
                        501L,
                        "Tower A",
                        1001L,
                        37.5123,
                        127.0456,
                        "Sample address",
                        5,
                        320,
                        LocalDate.of(2015, 3, 20))));

        mockMvc.perform(get("/api/v1/detail/{parcelId}/complexes", 1001L))
                .andExpect(status().isOk())
                .andDo(document(
                        "read-parcel-complexes-success",
                        pathParameters(parameterWithName("parcelId").description("Parcel id.")),
                        responseFields(complexSummaryFields()),
                        resource(builder()
                                .tag("Read")
                                .summary("Get parcel complexes")
                                .description("Returns selectable complexes under one parcel.")
                                .pathParameters(parameterWithName("parcelId").description("Parcel id."))
                                .responseFields(complexSummaryFields())
                                .build())));
    }

    @Test
    @DisplayName("GET /api/v1/complex/{complexId}와 GET /api/v1/complex/{complexId}/trades REST Docs를 생성한다")
    void documentComplexDetailAndTrades() throws Exception {
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
        given(tradeHistoryService.getComplexTradeList(502L, null, null))
                .willReturn(new TradeListResult(
                        1001L,
                        502L,
                        List.of(new TradeResult(
                                9101L, LocalDate.of(2025, 12, 20), new BigDecimal("59.93"), 90000L, "201", 9))));

        mockMvc.perform(get("/api/v1/complex/{complexId}", 502L))
                .andExpect(status().isOk())
                .andDo(document(
                        "read-complex-detail-success",
                        pathParameters(parameterWithName("complexId").description("Complex id.")),
                        responseFields(detailFields()),
                        resource(builder()
                                .tag("Read")
                                .summary("Get complex detail")
                                .description("Returns detail for one complex id.")
                                .pathParameters(parameterWithName("complexId").description("Complex id."))
                                .responseFields(detailFields())
                                .build())));

        mockMvc.perform(get("/api/v1/complex/{complexId}/trades", 502L))
                .andExpect(status().isOk())
                .andDo(document(
                        "read-complex-trades-success",
                        pathParameters(parameterWithName("complexId").description("Complex id.")),
                        queryParameters(tradePageQueryParameters()),
                        responseFields(tradeListFields()),
                        resource(builder()
                                .tag("Read")
                                .summary("Get complex trades")
                                .description("Returns active trades newest first for one complex id.")
                                .pathParameters(parameterWithName("complexId").description("Complex id."))
                                .queryParameters(tradePageQueryParameters())
                                .responseFields(tradeListFields())
                                .build())));
    }

    @Test
    @DisplayName("GET /api/v1/trade/{parcelId}/trend와 GET /api/v1/complex/{complexId}/trade-trend REST Docs를 생성한다")
    void documentTradeTrend() throws Exception {
        given(tradeHistoryService.getTradeTrend(1001L, 501L))
                .willReturn(List.of(new TradeTrendPoint("2025-12", 127500L, 2, 125000L, 130000L)));
        given(tradeHistoryService.getComplexTradeTrend(502L))
                .willReturn(List.of(new TradeTrendPoint("2025-12", 90000L, 1, 90000L, 90000L)));

        mockMvc.perform(get("/api/v1/trade/{parcelId}/trend", 1001L).param("complexId", "501"))
                .andExpect(status().isOk())
                .andDo(document(
                        "read-trade-trend-success",
                        pathParameters(parameterWithName("parcelId").description("Parcel id.")),
                        queryParameters(
                                parameterWithName("complexId").optional().description("Optional selected complex id.")),
                        responseFields(tradeTrendFields()),
                        resource(builder()
                                .tag("Read")
                                .summary("Get parcel trade monthly trend")
                                .description(
                                        "Returns monthly average trade price series (oldest first) for a parcel or scoped complex.")
                                .pathParameters(parameterWithName("parcelId").description("Parcel id."))
                                .queryParameters(parameterWithName("complexId")
                                        .optional()
                                        .description("Optional selected complex id."))
                                .responseFields(tradeTrendFields())
                                .build())));

        mockMvc.perform(get("/api/v1/complex/{complexId}/trade-trend", 502L))
                .andExpect(status().isOk())
                .andDo(document(
                        "read-complex-trade-trend-success",
                        pathParameters(parameterWithName("complexId").description("Complex id.")),
                        responseFields(tradeTrendFields()),
                        resource(builder()
                                .tag("Read")
                                .summary("Get complex trade monthly trend")
                                .description(
                                        "Returns monthly average trade price series (oldest first) for one complex id.")
                                .pathParameters(parameterWithName("complexId").description("Complex id."))
                                .responseFields(tradeTrendFields())
                                .build())));
    }

    private static org.springframework.restdocs.payload.FieldDescriptor[] complexSummaryFields() {
        return new org.springframework.restdocs.payload.FieldDescriptor[] {
            fieldWithPath("[].complexId").type(JsonFieldType.NUMBER).description("Complex id."),
            fieldWithPath("[].complexName").type(JsonFieldType.STRING).description("Complex display name."),
            fieldWithPath("[].parcelId").type(JsonFieldType.NUMBER).description("Parcel id."),
            fieldWithPath("[].latitude").type(JsonFieldType.NUMBER).optional().description("Display latitude."),
            fieldWithPath("[].longitude").type(JsonFieldType.NUMBER).optional().description("Display longitude."),
            fieldWithPath("[].address").type(JsonFieldType.STRING).optional().description("Parcel address."),
            fieldWithPath("[].dongCnt").type(JsonFieldType.NUMBER).optional().description("Building count."),
            fieldWithPath("[].unitCnt").type(JsonFieldType.NUMBER).optional().description("Household count."),
            fieldWithPath("[].useDate").type(JsonFieldType.STRING).optional().description("Use approval date.")
        };
    }

    private static org.springframework.restdocs.payload.FieldDescriptor[] detailFields() {
        return new org.springframework.restdocs.payload.FieldDescriptor[] {
            fieldWithPath("parcelId").type(JsonFieldType.NUMBER).description("Parcel id."),
            fieldWithPath("complexId")
                    .type(JsonFieldType.NUMBER)
                    .optional()
                    .description("Selected or representative complex id."),
            fieldWithPath("latitude").type(JsonFieldType.NUMBER).description("Detail display latitude."),
            fieldWithPath("longitude").type(JsonFieldType.NUMBER).description("Detail display longitude."),
            fieldWithPath("address").type(JsonFieldType.STRING).optional().description("Parcel address."),
            fieldWithPath("displayName")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("User-facing locality-combined complex name."),
            fieldWithPath("tradeName").type(JsonFieldType.STRING).optional().description("Representative trade name."),
            fieldWithPath("name").type(JsonFieldType.STRING).description("Representative complex name."),
            fieldWithPath("dongCnt").type(JsonFieldType.NUMBER).optional().description("Building count."),
            fieldWithPath("unitCnt").type(JsonFieldType.NUMBER).optional().description("Household count."),
            fieldWithPath("platArea").type(JsonFieldType.NUMBER).optional().description("Plat area."),
            fieldWithPath("archArea").type(JsonFieldType.NUMBER).optional().description("Architecture area."),
            fieldWithPath("totArea").type(JsonFieldType.NUMBER).optional().description("Total area."),
            fieldWithPath("bcRat").type(JsonFieldType.NUMBER).optional().description("Building coverage ratio."),
            fieldWithPath("vlRat").type(JsonFieldType.NUMBER).optional().description("Floor area ratio."),
            fieldWithPath("useDate").type(JsonFieldType.STRING).optional().description("Use approval date."),
            fieldWithPath("prediction")
                    .type(JsonFieldType.VARIES)
                    .optional()
                    .description("Optional F37 prediction result."),
            fieldWithPath("prediction.status")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("Prediction status: READY, PENDING, FAILED, or UNAVAILABLE."),
            fieldWithPath("prediction.modelVersion")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("Prediction model version."),
            fieldWithPath("prediction.predictedDealAmount")
                    .type(JsonFieldType.NUMBER)
                    .optional()
                    .description("Predicted total deal amount in 10,000 KRW units."),
            fieldWithPath("prediction.predictedPricePerM2")
                    .type(JsonFieldType.NUMBER)
                    .optional()
                    .description("Predicted price per square meter in 10,000 KRW units."),
            fieldWithPath("prediction.predictedPricePerPyeong")
                    .type(JsonFieldType.NUMBER)
                    .optional()
                    .description("Predicted price per pyeong in 10,000 KRW units."),
            fieldWithPath("prediction.intervalLow")
                    .type(JsonFieldType.NUMBER)
                    .optional()
                    .description("Lower predicted deal amount bound in 10,000 KRW units."),
            fieldWithPath("prediction.intervalHigh")
                    .type(JsonFieldType.NUMBER)
                    .optional()
                    .description("Upper predicted deal amount bound in 10,000 KRW units."),
            fieldWithPath("prediction.intervalBasis")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("Prediction interval basis."),
            fieldWithPath("prediction.targetAreaM2")
                    .type(JsonFieldType.NUMBER)
                    .optional()
                    .description("Prediction target exclusive area."),
            fieldWithPath("prediction.targetFloor")
                    .type(JsonFieldType.NUMBER)
                    .optional()
                    .description("Prediction target floor."),
            fieldWithPath("prediction.basisTradeId")
                    .type(JsonFieldType.NUMBER)
                    .optional()
                    .description("Basis trade id used for prediction features."),
            fieldWithPath("prediction.basisDealDate")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("Basis trade deal date."),
            fieldWithPath("prediction.generatedAt")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("Prediction status/result generation timestamp."),
            fieldWithPath("prediction.message")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("Non-sensitive prediction status message.")
        };
    }

    private static org.springframework.restdocs.payload.FieldDescriptor[] tradeListFields() {
        return new org.springframework.restdocs.payload.FieldDescriptor[] {
            fieldWithPath("parcelId").type(JsonFieldType.NUMBER).description("Parcel id."),
            fieldWithPath("complexId")
                    .type(JsonFieldType.NUMBER)
                    .optional()
                    .description("Selected complex id when scoped."),
            fieldWithPath("content").type(JsonFieldType.ARRAY).description("Trades on the current page, newest first."),
            fieldWithPath("content[].tradeId").type(JsonFieldType.NUMBER).description("Trade id."),
            fieldWithPath("content[].dealDate").type(JsonFieldType.STRING).description("Deal date."),
            fieldWithPath("content[].exclArea")
                    .type(JsonFieldType.NUMBER)
                    .optional()
                    .description("Exclusive area."),
            fieldWithPath("content[].dealAmount")
                    .type(JsonFieldType.NUMBER)
                    .description("Deal amount in 10,000 KRW units."),
            fieldWithPath("content[].aptDong")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("Apartment dong."),
            fieldWithPath("content[].floor")
                    .type(JsonFieldType.NUMBER)
                    .optional()
                    .description("Floor."),
            fieldWithPath("page").type(JsonFieldType.NUMBER).description("Zero-based page index."),
            fieldWithPath("size").type(JsonFieldType.NUMBER).description("Page size."),
            fieldWithPath("totalElements")
                    .type(JsonFieldType.NUMBER)
                    .description("Total trade count across all pages."),
            fieldWithPath("totalPages").type(JsonFieldType.NUMBER).description("Total page count.")
        };
    }

    private static org.springframework.restdocs.payload.FieldDescriptor[] tradeTrendFields() {
        return new org.springframework.restdocs.payload.FieldDescriptor[] {
            fieldWithPath("[].month").type(JsonFieldType.STRING).description("Trade month (YYYY-MM)."),
            fieldWithPath("[].avgAmount")
                    .type(JsonFieldType.NUMBER)
                    .description("Average deal amount in 10,000 KRW units."),
            fieldWithPath("[].count").type(JsonFieldType.NUMBER).description("Trade count in the month."),
            fieldWithPath("[].minAmount")
                    .type(JsonFieldType.NUMBER)
                    .description("Min deal amount in 10,000 KRW units."),
            fieldWithPath("[].maxAmount").type(JsonFieldType.NUMBER).description("Max deal amount in 10,000 KRW units.")
        };
    }

    private static org.springframework.restdocs.request.ParameterDescriptor[] tradePageQueryParameters() {
        return new org.springframework.restdocs.request.ParameterDescriptor[] {
            parameterWithName("page").optional().description("Zero-based page index. Default 0."),
            parameterWithName("size").optional().description("Page size. Default 25, max 100.")
        };
    }

    private static org.springframework.restdocs.request.ParameterDescriptor[] parcelTradeQueryParameters() {
        return new org.springframework.restdocs.request.ParameterDescriptor[] {
            parameterWithName("complexId").optional().description("Optional selected complex id."),
            parameterWithName("page").optional().description("Zero-based page index. Default 0."),
            parameterWithName("size").optional().description("Page size. Default 25, max 100.")
        };
    }
}
