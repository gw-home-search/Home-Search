package com.home.infrastructure.external.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.home.application.propertydetail.ComplexCenterReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
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
}
