package com.home.infrastructure.web.seo;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.application.prediction.PricePredictionUseCase;
import com.home.application.seo.SeoComplexResult;
import com.home.application.seo.SeoQueryService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SeoController.class)
@ActiveProfiles("test")
class SeoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SeoQueryService seoQueryService;

    @MockitoBean
    private PricePredictionUseCase predictionUseCase;

    @Test
    @DisplayName("단지 SEO 조회는 가격 예측을 호출하지 않는다")
    void complexSeoReadDoesNotInvokePrediction() throws Exception {
        given(seoQueryService.getComplex(501L))
                .willReturn(new SeoComplexResult(
                        501L,
                        "표본 아파트",
                        "서울 표본구 표본동 1",
                        true,
                        8,
                        740,
                        LocalDate.of(2015, 3, 20),
                        true,
                        List.of(new SeoComplexResult.Breadcrumb(1L, "서울")),
                        List.of()));

        mockMvc.perform(get("/internal/v1/seo/complexes/501"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complexId").value(501))
                .andExpect(jsonPath("$.indexable").value(true));

        verifyNoInteractions(predictionUseCase);
    }
}
