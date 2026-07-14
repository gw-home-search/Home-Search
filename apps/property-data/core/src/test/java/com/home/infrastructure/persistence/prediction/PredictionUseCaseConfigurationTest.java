package com.home.infrastructure.persistence.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.home.application.prediction.PredictionClient;
import com.home.application.prediction.PricePredictionUseCase;
import com.home.infrastructure.cache.prediction.RedisPredictionCacheRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class PredictionUseCaseConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    PredictionUseCaseConfiguration.class,
                    PricePredictionUseCase.class,
                    JdbcPredictionFeatureRepository.class,
                    RedisPredictionCacheRepository.class)
            .withBean(PredictionClient.class, () -> mock(PredictionClient.class));

    @Test
    @DisplayName("prediction이 활성화된 기본 설정에서 JdbcClient가 없으면 startup이 실패한다")
    void predictionFailsFastWithoutJdbcClient() {
        contextRunner
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    org.springframework.beans.factory.NoSuchBeanDefinitionException.class)
                            .hasMessageContaining("JdbcClient");
                });
    }

    @Test
    @DisplayName("prediction이 활성화된 기본 설정에서 Redis가 없으면 startup이 실패한다")
    void predictionFailsFastWithoutRedis() {
        contextRunner
                .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    org.springframework.beans.factory.NoSuchBeanDefinitionException.class)
                            .hasMessageContaining("StringRedisTemplate");
                });
    }
}
