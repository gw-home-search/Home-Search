package com.home.application.news.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.home.domain.news.MarketNewsQualityReviewStatus;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketNewsQualitySamplingServiceTest {

    private static final UUID REVIEW_SET_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174730");

    @Test
    @DisplayName("버전이 고정된 정책으로 결정적 품질 표본을 생성한다")
    void createsDeterministicSampleForVersionedPolicy() {
        MarketNewsQualitySamplingRepository repository = mock(MarketNewsQualitySamplingRepository.class);
        MarketNewsQualitySampleResult expected = new MarketNewsQualitySampleResult(
                REVIEW_SET_ID, MarketNewsQualityReviewStatus.READY, 220, 30, 17, 60, 40, 40, 50, 100);
        when(repository.createDeterministicSample(REVIEW_SET_ID, "NEWS_V2")).thenReturn(expected);

        assertThat(new MarketNewsQualitySamplingService(repository).sample(REVIEW_SET_ID, " NEWS_V2 "))
                .isEqualTo(expected);
        verify(repository).createDeterministicSample(REVIEW_SET_ID, "NEWS_V2");
    }

    @Test
    @DisplayName("버전이 없는 품질 정책을 거부한다")
    void rejectsUnversionedPolicy() {
        MarketNewsQualitySamplingRepository repository = mock(MarketNewsQualitySamplingRepository.class);

        assertThatThrownBy(() -> new MarketNewsQualitySamplingService(repository).sample(REVIEW_SET_ID, "latest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policyVersion");
    }
}
