package com.home.infrastructure.web.news;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.application.news.read.MarketNewsItemView;
import com.home.application.news.read.MarketNewsQueryService;
import com.home.application.news.read.MarketNewsReadResult;
import com.home.domain.news.MarketNewsCategory;
import com.home.domain.news.MarketNewsDataStatus;
import com.home.domain.news.MarketNewsRelationType;
import com.home.domain.news.MarketNewsScopeType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MarketNewsController.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "home.news.public.enabled=true")
class MarketNewsControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MarketNewsQueryService queryService;

    @Test
    @DisplayName("뉴스 모아보기는 공개 필드만 반환한다")
    void returnsPublishedNewsWithoutRawOrQualityEvidence() throws Exception {
        UUID snapshotId = UUID.fromString("d0fb824c-938e-4cc8-a674-336262ef4206");
        MarketNewsItemView item = new MarketNewsItemView(
                31L,
                MarketNewsCategory.POLICY,
                "서울 아파트 정책 발표",
                Instant.parse("2026-07-24T06:00:00Z"),
                "https://news.example.test/article/31",
                "11",
                "서울특별시",
                null);
        given(queryService.list(MarketNewsScopeType.SIDO, "11", MarketNewsCategory.ALL, null, 20))
                .willReturn(new MarketNewsReadResult(
                        snapshotId,
                        Instant.parse("2026-07-24T06:31:00Z"),
                        Instant.parse("2026-07-24T06:30:00Z"),
                        MarketNewsDataStatus.FRESH,
                        MarketNewsScopeType.SIDO,
                        "11",
                        MarketNewsCategory.ALL,
                        List.of(item),
                        "opaque-next"));

        mockMvc.perform(get("/api/v1/insights/news").param("scope", "SIDO").param("regionCode", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").value(snapshotId.toString()))
                .andExpect(jsonPath("$.dataStatus").value("FRESH"))
                .andExpect(jsonPath("$.scope.type").value("SIDO"))
                .andExpect(jsonPath("$.category").value("ALL"))
                .andExpect(jsonPath("$.items[0].articleId").value(31))
                .andExpect(jsonPath("$.items[0].title").value("서울 아파트 정책 발표"))
                .andExpect(jsonPath("$.items[0].url").value("https://news.example.test/article/31"))
                .andExpect(jsonPath("$.items[0].description").doesNotExist())
                .andExpect(jsonPath("$.items[0].rawTitle").doesNotExist())
                .andExpect(jsonPath("$.items[0].relationType").doesNotExist())
                .andExpect(jsonPath("$.excludedCount").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").value("opaque-next"));
    }

    @Test
    @DisplayName("단지 뉴스는 관련성 필드를 포함하고 최대 다섯 건만 반환한다")
    void returnsAtMostFiveComplexNewsItems() throws Exception {
        given(queryService.complexNews(501L))
                .willReturn(List.of(new MarketNewsItemView(
                        31L,
                        MarketNewsCategory.TRANSACTION_PRICE,
                        "래미안 테스트 거래 증가",
                        Instant.parse("2026-07-24T06:00:00Z"),
                        "https://news.example.test/article/31",
                        "11",
                        "서울특별시",
                        MarketNewsRelationType.DIRECT_COMPLEX)));

        mockMvc.perform(get("/api/v1/complex/501/news"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].relationType").value("DIRECT_COMPLEX"))
                .andExpect(jsonPath("$[0].articleId").value(31));
    }

    @Test
    @DisplayName("SIDO without regionCode와 51개 요청은 400 ProblemDetail이다")
    void validatesScopeAndLimit() throws Exception {
        mockMvc.perform(get("/api/v1/insights/news").param("scope", "SIDO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(get("/api/v1/insights/news").param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
