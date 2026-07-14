package com.home.infrastructure.persistence.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.home.application.prediction.PredictionClient;
import com.home.application.prediction.PricePredictionUseCase;
import com.home.infrastructure.cache.prediction.RedisPredictionCacheRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.ObjectMapper;

class PredictionUseCaseConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    PredictionUseCaseConfiguration.class,
                    PricePredictionUseCase.class,
                    JdbcPredictionFeatureRepository.class,
                    RedisPredictionCacheRepository.class)
            .withBean(PredictionClient.class, () -> mock(PredictionClient.class))
            .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new);

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

    @Test
    @DisplayName("prediction executor는 bounded queue를 가진 Spring-managed executor다")
    void predictionExecutorIsSpringManagedAndBounded() {
        contextRunner
                .withPropertyValues("home.prediction.executor.threads=3", "home.prediction.executor.queue-capacity=11")
                .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ThreadPoolTaskExecutor executor =
                            context.getBean("predictionExecutor", ThreadPoolTaskExecutor.class);
                    assertThat(executor.getCorePoolSize()).isEqualTo(3);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(3);
                    assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity())
                            .isEqualTo(11);
                });
    }
}
