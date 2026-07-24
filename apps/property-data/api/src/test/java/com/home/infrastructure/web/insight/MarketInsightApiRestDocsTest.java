package com.home.infrastructure.web.insight;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static com.epages.restdocs.apispec.ResourceSnippetParameters.builder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.application.insight.read.MarketInsightQueryService;
import com.home.application.insight.read.MarketInsightReadResult;
import com.home.domain.insight.MarketInsightScopeType;
import java.time.LocalDate;
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
@WebMvcTest(MarketInsightController.class)
@AutoConfigureRestDocs
@ActiveProfiles("test")
class MarketInsightApiRestDocsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MarketInsightQueryService queryService;

    @Test
    void documentLatestUnavailable() throws Exception {
        LocalDate date = LocalDate.parse("2026-07-22");
        given(queryService.latest(MarketInsightScopeType.NATIONWIDE, null, date, 10))
                .willReturn(MarketInsightReadResult.unavailable(MarketInsightScopeType.NATIONWIDE, null, date));

        mockMvc.perform(get("/api/v1/insights/trades/latest").param("date", date.toString()))
                .andExpect(status().isOk())
                .andDo(document(
                        "market-insight-latest-unavailable",
                        resource(builder()
                                .tag("Market insight")
                                .summary("Get latest disclosed trade insights")
                                .queryParameters(
                                        parameterWithName("scope").optional().description("NATIONWIDE or SIDO."),
                                        parameterWithName("regionCode")
                                                .optional()
                                                .description("Required for SIDO."),
                                        parameterWithName("date").optional().description("Seoul date in YYYY-MM-DD."),
                                        parameterWithName("limit")
                                                .optional()
                                                .description("Per-section limit, 1 through 50."))
                                .responseFields(
                                        fieldWithPath("snapshotId")
                                                .optional()
                                                .type(JsonFieldType.STRING)
                                                .description("Published snapshot id."),
                                        fieldWithPath("periodStart")
                                                .type(JsonFieldType.STRING)
                                                .description("Period start."),
                                        fieldWithPath("periodEnd")
                                                .type(JsonFieldType.STRING)
                                                .description("Period end."),
                                        fieldWithPath("generatedAt")
                                                .optional()
                                                .type(JsonFieldType.STRING)
                                                .description("Generation timestamp."),
                                        fieldWithPath("dataCutoff")
                                                .optional()
                                                .type(JsonFieldType.STRING)
                                                .description("Collection cutoff."),
                                        fieldWithPath("dataStatus")
                                                .type(JsonFieldType.STRING)
                                                .description("FRESH, STALE, or UNAVAILABLE."),
                                        fieldWithPath("scope.type")
                                                .type(JsonFieldType.STRING)
                                                .description("Scope type."),
                                        fieldWithPath("scope.regionCode")
                                                .optional()
                                                .type(JsonFieldType.STRING)
                                                .description("SIDO code."),
                                        fieldWithPath("quality.missingRegistrationDateCount")
                                                .type(JsonFieldType.NUMBER)
                                                .description(
                                                        "Active rolling candidates included by deal-date fallback because registration date is missing."),
                                        fieldWithPath("quality.invalidRegistrationDateCount")
                                                .type(JsonFieldType.NUMBER)
                                                .description(
                                                        "Active rolling candidates included by deal-date fallback because registration date is invalid."),
                                        fieldWithPath("quality.missingCancellationDateCount")
                                                .type(JsonFieldType.NUMBER)
                                                .description(
                                                        "Cancellations excluded because cancellation date is missing."),
                                        fieldWithPath("quality.invalidCancellationDateCount")
                                                .type(JsonFieldType.NUMBER)
                                                .description(
                                                        "Cancellations excluded because cancellation date is invalid."),
                                        fieldWithPath("quality.excludedCount")
                                                .type(JsonFieldType.NUMBER)
                                                .description("Cancellation date-quality exclusions."),
                                        fieldWithPath("newTrades")
                                                .type(JsonFieldType.ARRAY)
                                                .description("Newly disclosed trades."),
                                        fieldWithPath("highestDeals")
                                                .type(JsonFieldType.ARRAY)
                                                .description("Highest deals."),
                                        fieldWithPath("recordHighs")
                                                .type(JsonFieldType.ARRAY)
                                                .description("Exact-area record highs."),
                                        fieldWithPath("previousRises")
                                                .type(JsonFieldType.ARRAY)
                                                .description("Previous-date rises."),
                                        fieldWithPath("previousFalls")
                                                .type(JsonFieldType.ARRAY)
                                                .description("Previous-date falls."),
                                        fieldWithPath("cancellations")
                                                .type(JsonFieldType.ARRAY)
                                                .description("Cancellation corrections."))
                                .build())));
    }

    @Test
    void documentWeeklyUnavailable() throws Exception {
        LocalDate periodEnd = LocalDate.parse("2026-07-23");
        given(queryService.weekly(
                        org.mockito.ArgumentMatchers.eq(MarketInsightScopeType.NATIONWIDE),
                        org.mockito.ArgumentMatchers.isNull(),
                        any(LocalDate.class),
                        org.mockito.ArgumentMatchers.eq(10)))
                .willReturn(
                        MarketInsightReadResult.unavailableRolling(MarketInsightScopeType.NATIONWIDE, null, periodEnd));

        mockMvc.perform(get("/api/v1/insights/trades/weekly").param("scope", "NATIONWIDE"))
                .andExpect(status().isOk())
                .andDo(document(
                        "market-insight-weekly-unavailable",
                        resource(builder()
                                .tag("Market insight")
                                .summary("Get latest rolling 7-day trade insights")
                                .queryParameters(
                                        parameterWithName("scope").optional().description("NATIONWIDE or SIDO."),
                                        parameterWithName("regionCode")
                                                .optional()
                                                .description("Required for SIDO."),
                                        parameterWithName("limit")
                                                .optional()
                                                .description("Per-section limit, 1 through 50."))
                                .responseFields(
                                        fieldWithPath("snapshotId")
                                                .optional()
                                                .type(JsonFieldType.STRING)
                                                .description("Published snapshot id."),
                                        fieldWithPath("periodStart")
                                                .type(JsonFieldType.STRING)
                                                .description("Rolling period start, periodEnd minus six days."),
                                        fieldWithPath("periodEnd")
                                                .type(JsonFieldType.STRING)
                                                .description("Source DAILY execution run date."),
                                        fieldWithPath("generatedAt")
                                                .optional()
                                                .type(JsonFieldType.STRING)
                                                .description("Generation timestamp."),
                                        fieldWithPath("dataCutoff")
                                                .optional()
                                                .type(JsonFieldType.STRING)
                                                .description("Collection cutoff."),
                                        fieldWithPath("dataStatus")
                                                .type(JsonFieldType.STRING)
                                                .description("FRESH, STALE, or UNAVAILABLE."),
                                        fieldWithPath("scope.type")
                                                .type(JsonFieldType.STRING)
                                                .description("Scope type."),
                                        fieldWithPath("scope.regionCode")
                                                .optional()
                                                .type(JsonFieldType.STRING)
                                                .description("SIDO code."),
                                        fieldWithPath("quality.missingRegistrationDateCount")
                                                .type(JsonFieldType.NUMBER)
                                                .description(
                                                        "Active rolling candidates included by deal-date fallback because registration date is missing."),
                                        fieldWithPath("quality.invalidRegistrationDateCount")
                                                .type(JsonFieldType.NUMBER)
                                                .description(
                                                        "Active rolling candidates included by deal-date fallback because registration date is invalid."),
                                        fieldWithPath("quality.missingCancellationDateCount")
                                                .type(JsonFieldType.NUMBER)
                                                .description(
                                                        "Cancellations excluded because cancellation date is missing."),
                                        fieldWithPath("quality.invalidCancellationDateCount")
                                                .type(JsonFieldType.NUMBER)
                                                .description(
                                                        "Cancellations excluded because cancellation date is invalid."),
                                        fieldWithPath("quality.excludedCount")
                                                .type(JsonFieldType.NUMBER)
                                                .description("Cancellation date-quality exclusions."),
                                        fieldWithPath("newTrades")
                                                .type(JsonFieldType.ARRAY)
                                                .description(
                                                        "Trades selected by registration date or active deal-date fallback in the rolling period."),
                                        fieldWithPath("highestDeals")
                                                .type(JsonFieldType.ARRAY)
                                                .description(
                                                        "Highest deals selected by registration date or active deal-date fallback."),
                                        fieldWithPath("recordHighs")
                                                .type(JsonFieldType.ARRAY)
                                                .description("Exact-area record highs."),
                                        fieldWithPath("previousRises")
                                                .type(JsonFieldType.ARRAY)
                                                .description("Previous-date rises."),
                                        fieldWithPath("previousFalls")
                                                .type(JsonFieldType.ARRAY)
                                                .description("Previous-date falls."),
                                        fieldWithPath("cancellations")
                                                .type(JsonFieldType.ARRAY)
                                                .description("Cancellation corrections."))
                                .build())));
    }
}
