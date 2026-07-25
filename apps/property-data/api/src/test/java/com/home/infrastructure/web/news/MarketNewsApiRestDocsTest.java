package com.home.infrastructure.web.news;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static com.epages.restdocs.apispec.ResourceSnippetParameters.builder;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.application.news.read.MarketNewsItemView;
import com.home.application.news.read.MarketNewsQueryService;
import com.home.application.news.read.MarketNewsReadResult;
import com.home.domain.news.MarketNewsCategory;
import com.home.domain.news.MarketNewsRelationType;
import com.home.domain.news.MarketNewsScopeType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@Tag("restDocs")
@WebMvcTest(MarketNewsController.class)
@AutoConfigureRestDocs
@ActiveProfiles("test")
@TestPropertySource(properties = "home.news.public.enabled=true")
class MarketNewsApiRestDocsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MarketNewsQueryService queryService;

    @Test
    @DisplayName("최근 30일 뉴스 미발행 응답을 문서화한다")
    void documentNewsUnavailable() throws Exception {
        given(queryService.list(MarketNewsScopeType.NATIONWIDE, null, MarketNewsCategory.ALL, null, 20))
                .willReturn(
                        MarketNewsReadResult.unavailable(MarketNewsScopeType.NATIONWIDE, null, MarketNewsCategory.ALL));

        mockMvc.perform(get("/api/v1/insights/news"))
                .andExpect(status().isOk())
                .andDo(document(
                        "market-news-unavailable",
                        resource(builder()
                                .tag("Market news")
                                .summary("Get published real-estate news")
                                .queryParameters(
                                        parameterWithName("scope").optional().description("NATIONWIDE or SIDO."),
                                        parameterWithName("regionCode")
                                                .optional()
                                                .description("Required root SIDO code for SIDO."),
                                        parameterWithName("category")
                                                .optional()
                                                .description("ALL or one fixed news category."),
                                        parameterWithName("cursor").optional().description("Opaque pagination cursor."),
                                        parameterWithName("limit").optional().description("Page size, 1 through 50."))
                                .responseFields(
                                        fieldWithPath("snapshotId")
                                                .optional()
                                                .type(JsonFieldType.STRING)
                                                .description("Published snapshot id."),
                                        fieldWithPath("generatedAt")
                                                .optional()
                                                .type(JsonFieldType.STRING)
                                                .description("Publication timestamp."),
                                        fieldWithPath("dataCutoff")
                                                .optional()
                                                .type(JsonFieldType.STRING)
                                                .description("Provider data cutoff."),
                                        fieldWithPath("dataStatus")
                                                .type(JsonFieldType.STRING)
                                                .description("FRESH, STALE, or UNAVAILABLE."),
                                        fieldWithPath("scope.type")
                                                .type(JsonFieldType.STRING)
                                                .description("NATIONWIDE or SIDO."),
                                        fieldWithPath("scope.regionCode")
                                                .optional()
                                                .type(JsonFieldType.STRING)
                                                .description("Root SIDO code."),
                                        fieldWithPath("category")
                                                .type(JsonFieldType.STRING)
                                                .description("Applied category."),
                                        fieldWithPath("items")
                                                .type(JsonFieldType.ARRAY)
                                                .description("Published news items."),
                                        fieldWithPath("nextCursor")
                                                .optional()
                                                .type(JsonFieldType.STRING)
                                                .description("Opaque next-page cursor."))
                                .build())));
    }

    @Test
    @DisplayName("단지 관련 뉴스 최대 5개 응답을 문서화한다")
    void documentComplexNews() throws Exception {
        given(queryService.complexNews(501L))
                .willReturn(List.of(new MarketNewsItemView(
                        91L,
                        MarketNewsCategory.TRANSACTION_PRICE,
                        "서울 테스트아파트 거래 소식",
                        Instant.parse("2026-07-24T09:00:00Z"),
                        "https://news.example.com/91",
                        "11",
                        "서울특별시",
                        MarketNewsRelationType.DIRECT_COMPLEX)));

        mockMvc.perform(get("/api/v1/complex/{complexId}/news", 501L))
                .andExpect(status().isOk())
                .andDo(document(
                        "complex-market-news",
                        resource(builder()
                                .tag("Market news")
                                .summary("Get up to five related news items for a complex")
                                .pathParameters(parameterWithName("complexId").description("Operational complex id."))
                                .responseFields(
                                        fieldWithPath("[].articleId")
                                                .type(JsonFieldType.NUMBER)
                                                .description("Public article id."),
                                        fieldWithPath("[].category")
                                                .type(JsonFieldType.STRING)
                                                .description("Fixed news category."),
                                        fieldWithPath("[].title")
                                                .type(JsonFieldType.STRING)
                                                .description("Sanitized title."),
                                        fieldWithPath("[].providedAt")
                                                .type(JsonFieldType.STRING)
                                                .description("Provider timestamp."),
                                        fieldWithPath("[].url")
                                                .type(JsonFieldType.STRING)
                                                .description("Provider-returned HTTP(S) original URL."),
                                        fieldWithPath("[].region.code")
                                                .optional()
                                                .type(JsonFieldType.STRING)
                                                .description("Related region code."),
                                        fieldWithPath("[].region.name")
                                                .optional()
                                                .type(JsonFieldType.STRING)
                                                .description("Related region name."),
                                        fieldWithPath("[].relationType")
                                                .type(JsonFieldType.STRING)
                                                .description("DIRECT_COMPLEX, SAME_DONG, or SAME_SIGUNGU."))
                                .build())));
    }
}
