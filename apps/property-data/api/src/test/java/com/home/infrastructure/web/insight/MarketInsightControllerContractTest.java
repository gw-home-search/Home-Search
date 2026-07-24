package com.home.infrastructure.web.insight;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.application.insight.read.InvalidInsightQueryException;
import com.home.application.insight.read.MarketInsightQueryService;
import com.home.application.insight.read.MarketInsightReadResult;
import com.home.application.insight.read.MarketInsightTradeItemView;
import com.home.domain.insight.MarketInsightDataStatus;
import com.home.domain.insight.MarketInsightMetricType;
import com.home.domain.insight.MarketInsightQuality;
import com.home.domain.insight.MarketInsightScopeType;
import com.home.domain.insight.MarketInsightTradeStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MarketInsightController.class)
@ActiveProfiles("test")
class MarketInsightControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MarketInsightQueryService queryService;

    @Test
    @DisplayName("weekly 조회에 제거된 weekStart를 전달하면 400 ProblemDetail을 반환한다")
    void weeklyRejectsRemovedWeekStartParameter() throws Exception {
        LocalDate weekStart = LocalDate.parse("2026-07-13");
        given(queryService.weekly(MarketInsightScopeType.SIDO, "11", weekStart, 10))
                .willReturn(MarketInsightReadResult.unavailableWeekly(MarketInsightScopeType.SIDO, "11", weekStart));

        mockMvc.perform(get("/api/v1/insights/trades/weekly")
                        .param("scope", "SIDO")
                        .param("regionCode", "11")
                        .param("weekStart", weekStart.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("weekStart 없는 weekly 조회는 최신 rolling 7일 six-section을 반환한다")
    void weeklyWithoutWeekStartReturnsLatestRollingSevenDays() throws Exception {
        LocalDate periodEnd = LocalDate.parse("2026-07-23");
        given(queryService.weekly(eq(MarketInsightScopeType.SIDO), eq("11"), any(LocalDate.class), eq(10)))
                .willReturn(MarketInsightReadResult.unavailableRolling(MarketInsightScopeType.SIDO, "11", periodEnd));

        mockMvc.perform(get("/api/v1/insights/trades/weekly")
                        .param("scope", "SIDO")
                        .param("regionCode", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodStart").value("2026-07-17"))
                .andExpect(jsonPath("$.periodEnd").value("2026-07-23"))
                .andExpect(jsonPath("$.quality.excludedCount").value(0))
                .andExpect(jsonPath("$.newTrades").isEmpty())
                .andExpect(jsonPath("$.highestDeals").isEmpty())
                .andExpect(jsonPath("$.recordHighs").isEmpty())
                .andExpect(jsonPath("$.previousRises").isEmpty())
                .andExpect(jsonPath("$.previousFalls").isEmpty())
                .andExpect(jsonPath("$.cancellations").isEmpty());
    }

    @Test
    @DisplayName("유효한 latest 조회에 snapshot이 없으면 UNAVAILABLE과 빈 section을 반환한다")
    void validRequestWithoutSnapshotReturnsUnavailableEmptySections() throws Exception {
        given(queryService.latest(MarketInsightScopeType.NATIONWIDE, null, LocalDate.parse("2026-07-22"), 10))
                .willReturn(MarketInsightReadResult.unavailable(
                        MarketInsightScopeType.NATIONWIDE, null, LocalDate.parse("2026-07-22")));

        mockMvc.perform(get("/api/v1/insights/trades/latest").param("date", "2026-07-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.scope.type").value("NATIONWIDE"))
                .andExpect(jsonPath("$.newTrades").isEmpty())
                .andExpect(jsonPath("$.highestDeals").isEmpty())
                .andExpect(jsonPath("$.recordHighs").isEmpty())
                .andExpect(jsonPath("$.previousRises").isEmpty())
                .andExpect(jsonPath("$.previousFalls").isEmpty())
                .andExpect(jsonPath("$.cancellations").isEmpty());
    }

    @Test
    @DisplayName("published 응답은 여섯 section의 additive 거래 필드를 노출하고 내부 식별자는 숨긴다")
    void publishedResponseKeepsTheAdditivePublicItemContract() throws Exception {
        LocalDate date = LocalDate.parse("2026-07-22");
        MarketInsightTradeItemView item = new MarketInsightTradeItemView(
                MarketInsightMetricType.CANCELLATION_CORRECTION,
                1,
                501L,
                1001L,
                "래미안 테스트",
                "서울특별시",
                "강남구",
                new BigDecimal("84.99"),
                255000L,
                date.minusDays(21),
                Instant.parse("2026-07-22T03:14:15Z"),
                LocalDate.parse("2026-07-22"),
                LocalDate.parse("2026-07-23"),
                244000L,
                date.minusMonths(1),
                11000L,
                new BigDecimal("4.508197"),
                1,
                1,
                1,
                MarketInsightTradeStatus.CANCELED,
                Instant.parse("2026-07-22T04:00:00Z"));
        List<MarketInsightTradeItemView> items = List.of(item);
        given(queryService.latest(MarketInsightScopeType.SIDO, "11", date, 10))
                .willReturn(new MarketInsightReadResult(
                        UUID.fromString("d0fb824c-938e-4cc8-a674-336262ef4206"),
                        date,
                        date,
                        Instant.parse("2026-07-22T06:31:00Z"),
                        Instant.parse("2026-07-22T06:30:00Z"),
                        MarketInsightDataStatus.FRESH,
                        MarketInsightScopeType.SIDO,
                        "11",
                        new MarketInsightQuality(1, 2, 3, 4),
                        items,
                        items,
                        items,
                        items,
                        items,
                        items));

        mockMvc.perform(get("/api/v1/insights/trades/latest")
                        .param("scope", "SIDO")
                        .param("regionCode", "11")
                        .param("date", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.regionCode").value("11"))
                .andExpect(jsonPath("$.recordHighs[0].exclArea").value(84.99))
                .andExpect(jsonPath("$.recordHighs[0].registrationDate").value("2026-07-22"))
                .andExpect(jsonPath("$.previousRises[0].comparisonSampleCount").value(1))
                .andExpect(jsonPath("$.cancellations[0].tradeStatus").value("CANCELED"))
                .andExpect(jsonPath("$.cancellations[0].cancellationDate").value("2026-07-23"))
                .andExpect(jsonPath("$.cancellations[0].canceledAt").value("2026-07-22T04:00:00Z"))
                .andExpect(jsonPath("$.quality.missingRegistrationDateCount").value(1))
                .andExpect(jsonPath("$.quality.invalidRegistrationDateCount").value(2))
                .andExpect(jsonPath("$.quality.missingCancellationDateCount").value(3))
                .andExpect(jsonPath("$.quality.invalidCancellationDateCount").value(4))
                .andExpect(jsonPath("$.quality.excludedCount").value(7))
                .andExpect(jsonPath("$.newTrades[0].complex_pk").doesNotExist())
                .andExpect(jsonPath("$.newTrades[0].sourceKey").doesNotExist())
                .andExpect(jsonPath("$.newTrades[0].requestId").doesNotExist());
    }

    @Test
    @DisplayName("SIDO scope에 regionCode가 없거나 limit이 50을 넘으면 400 ProblemDetail이다")
    void invalidScopeRegionOrLimitReturnsProblemDetail() throws Exception {
        given(queryService.latest(MarketInsightScopeType.SIDO, null, LocalDate.parse("2026-07-22"), 10))
                .willThrow(new InvalidInsightQueryException("regionCode is required only for SIDO scope"));
        mockMvc.perform(get("/api/v1/insights/trades/latest")
                        .param("scope", "SIDO")
                        .param("date", "2026-07-22"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(get("/api/v1/insights/trades/latest").param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
