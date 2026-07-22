package com.home.infrastructure.web.insight;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.application.insight.read.InvalidInsightQueryException;
import com.home.application.insight.read.MarketInsightQueryService;
import com.home.application.insight.read.MarketInsightReadResult;
import com.home.domain.insight.MarketInsightScopeType;
import java.time.LocalDate;
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
