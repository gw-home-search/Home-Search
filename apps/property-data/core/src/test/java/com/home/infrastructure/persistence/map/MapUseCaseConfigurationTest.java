package com.home.infrastructure.persistence.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.map.MapQueryUseCase;
import com.home.application.map.MapUseCase;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;

class MapUseCaseConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(MapUseCaseConfiguration.class);

	@Test
	@DisplayName("map read persistence는 JdbcClient가 없으면 empty marker fallback 대신 startup 실패로 드러난다")
	void mapUseCaseFailsFastWithoutJdbcClient() {
		contextRunner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
				.hasRootCauseInstanceOf(IllegalStateException.class)
				.hasMessageContaining("JdbcClient is required for map read persistence");
		});
	}

	@Test
	@DisplayName("JdbcClient가 있으면 map query use case를 구성한다")
	void mapUseCaseUsesJdbcRepositoriesWhenJdbcClientExists() {
		contextRunner
			.withBean(JdbcClient.class, () -> mock(JdbcClient.class))
			.run(context -> {
				assertThat(context).hasSingleBean(MapUseCase.class);
				assertThat(context.getBean(MapUseCase.class)).isInstanceOf(MapQueryUseCase.class);
				assertThat(complexMarkerRepository(context.getBean(MapUseCase.class)))
					.isInstanceOf(JdbcMapMarkerRepository.class);
			});
	}

	@Test
	@DisplayName("marker cache가 켜지면 Redis cache repository로 JDBC marker repository를 감싼다")
	void markerCacheWrapsJdbcMarkerRepositoryWhenEnabled() {
		contextRunner
			.withPropertyValues("home.map.marker-cache.enabled=true")
			.withBean(JdbcClient.class, () -> mock(JdbcClient.class))
			.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
			.withBean(ObjectMapper.class, ObjectMapper::new)
			.withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
			.run(context -> {
				assertThat(context).hasSingleBean(MapUseCase.class);
				assertThat(complexMarkerRepository(context.getBean(MapUseCase.class)))
					.isInstanceOf(RedisCachingComplexMarkerRepository.class);
			});
	}

	@Test
	@DisplayName("marker cache TTL 기본값은 운영 후보인 5분이다")
	void markerCacheDefaultTtlIsFiveMinutes() {
		contextRunner
			.withPropertyValues("home.map.marker-cache.enabled=true")
			.withBean(JdbcClient.class, () -> mock(JdbcClient.class))
			.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
			.withBean(ObjectMapper.class, ObjectMapper::new)
			.withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
			.run(context -> {
				Object repository = complexMarkerRepository(context.getBean(MapUseCase.class));

				assertThat(ReflectionTestUtils.getField(repository, "ttl"))
					.isEqualTo(Duration.ofMinutes(5));
			});
	}

	private Object complexMarkerRepository(MapUseCase mapUseCase) {
		return ReflectionTestUtils.getField(mapUseCase, "complexMarkerRepository");
	}
}
