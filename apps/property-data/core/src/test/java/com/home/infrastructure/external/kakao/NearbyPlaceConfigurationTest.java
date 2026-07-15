package com.home.infrastructure.external.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.home.application.place.NearbyPlaceCenterReader;
import com.home.application.place.NearbyPlaceProvider;
import com.home.application.propertydetail.ComplexCenter;
import com.home.application.propertydetail.ComplexCenterReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.ObjectMapper;

class NearbyPlaceConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    NearbyPlaceConfiguration.class,
                    com.home.application.place.NearbyPlaceQueryService.class,
                    ObservedNearbyPlaceUseCase.class)
            .withBean(ComplexCenterReader.class, () -> mock(ComplexCenterReader.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    @DisplayName("Kakao nearby 기능을 켰는데 REST API key가 없으면 startup이 실패한다")
    void enabledNearbyPlaceFailsFastWithoutRestApiKey() {
        contextRunner
                .withPropertyValues("home.place.kakao.enabled=true")
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "home.place.kakao.rest-api-key must be configured when home.place.kakao.enabled=true");
                });
    }

    @Test
    @DisplayName("Kakao nearby 기능을 켰는데 Redis가 없으면 startup이 실패한다")
    void enabledNearbyPlaceFailsFastWithoutRedis() {
        contextRunner
                .withPropertyValues("home.place.kakao.enabled=true", "home.place.kakao.rest-api-key=test-key")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    org.springframework.beans.factory.NoSuchBeanDefinitionException.class)
                            .hasMessageContaining("StringRedisTemplate");
                });
    }

    @Test
    @DisplayName("nearby executor는 bounded queue를 가진 Spring-managed executor다")
    void nearbyExecutorIsSpringManagedAndBounded() {
        contextRunner
                .withPropertyValues("home.place.kakao.executor.threads=2", "home.place.kakao.executor.queue-capacity=7")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ThreadPoolTaskExecutor executor =
                            context.getBean("nearbyPlaceExecutor", ThreadPoolTaskExecutor.class);
                    assertThat(executor.getCorePoolSize()).isEqualTo(2);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(2);
                    assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity())
                            .isEqualTo(7);
                });
    }

    @Test
    @DisplayName("nearby executor 기본 동시성은 8종 cold-cache 조회를 위해 4다")
    void nearbyExecutorDefaultsToFourThreads() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskExecutor executor = context.getBean("nearbyPlaceExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(4);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
        });
    }

    @Test
    @DisplayName("Kakao nearby 기능이 활성화되면 provider와 좌표 reader를 조립한다")
    void enabledNearbyPlaceBuildsProviderAndCenterReader() {
        contextRunner
                .withPropertyValues("home.place.kakao.enabled=true", "home.place.kakao.rest-api-key=test-key")
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(NearbyPlaceProvider.class);
                    ComplexCenterReader centerReader = context.getBean(ComplexCenterReader.class);
                    given(centerReader.findComplexCenter(42L)).willReturn(Optional.of(new ComplexCenter(37.5, 127.0)));
                    assertThat(context.getBean(NearbyPlaceCenterReader.class).findComplexCenter(42L))
                            .hasValueSatisfying(center -> {
                                assertThat(center.complexId()).isEqualTo(42L);
                                assertThat(center.lat()).isEqualTo(37.5);
                                assertThat(center.lng()).isEqualTo(127.0);
                            });
                });
    }
}
