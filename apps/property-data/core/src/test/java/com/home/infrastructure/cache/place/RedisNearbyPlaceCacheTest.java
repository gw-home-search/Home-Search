package com.home.infrastructure.cache.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.place.NearbyPlaceProviderResult;
import com.home.application.place.NearbyPlacePoint;
import com.home.domain.place.NearbyPlaceCategory;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisNearbyPlaceCacheTest {

	private static final Duration TTL = Duration.ofHours(24);
	private static final NearbyPlaceCacheKey KEY = NearbyPlaceCacheKey.from(
		new NearbyPlacePoint(37.321, 127.109),
		800,
		NearbyPlaceCategory.CAFE
	);
	private static final NearbyPlaceProviderResult EMPTY_RESULT = new NearbyPlaceProviderResult(
		NearbyPlaceCategory.CAFE,
		0,
		Instant.parse("2026-07-13T03:00:00Z"),
		List.of()
	);

	@Test
	@DisplayName("Redis nearby cache는 expired miss와 empty-result hit를 구분한다")
	void distinguishesMissAndEmptyResultHit() throws Exception {
		Fixture fixture = fixture();
		when(fixture.values().get(KEY.redisKey()))
			.thenReturn(null)
			.thenReturn(fixture.objectMapper().writeValueAsString(EMPTY_RESULT));

		assertThat(fixture.cache().find(KEY)).isEmpty();
		assertThat(fixture.cache().find(KEY)).contains(EMPTY_RESULT);
		assertThat(fixture.registry().counter(
			"home.search.nearby.place.cache.requests", "result", "miss"
		).count()).isEqualTo(1);
		assertThat(fixture.registry().counter(
			"home.search.nearby.place.cache.requests", "result", "hit"
		).count()).isEqualTo(1);
	}

	@Test
	@DisplayName("깨진 JSON cache entry는 삭제하고 miss로 degrade한다")
	void discardsMalformedJson() {
		Fixture fixture = fixture();
		when(fixture.values().get(KEY.redisKey())).thenReturn("{not-json");

		assertThat(fixture.cache().find(KEY)).isEmpty();
		verify(fixture.redisTemplate()).delete(KEY.redisKey());
		assertThat(fixture.registry().counter(
			"home.search.nearby.place.cache.requests", "result", "corrupt"
		).count()).isEqualTo(1);
	}

	@Test
	@DisplayName("category가 다른 cache payload는 삭제하고 반환하지 않는다")
	void discardsPayloadForAnotherCategory() throws Exception {
		Fixture fixture = fixture();
		NearbyPlaceProviderResult wrongCategory = new NearbyPlaceProviderResult(
			NearbyPlaceCategory.RESTAURANT,
			0,
			EMPTY_RESULT.retrievedAt(),
			List.of()
		);
		when(fixture.values().get(KEY.redisKey()))
			.thenReturn(fixture.objectMapper().writeValueAsString(wrongCategory));

		assertThat(fixture.cache().find(KEY)).isEmpty();
		verify(fixture.redisTemplate()).delete(KEY.redisKey());
	}

	@Test
	@DisplayName("Redis read 장애는 provider 호출이 가능한 cache miss로 degrade한다")
	void degradesReadFailureToMiss() {
		Fixture fixture = fixture();
		when(fixture.values().get(KEY.redisKey())).thenThrow(new IllegalStateException("redis unavailable"));

		assertThat(fixture.cache().find(KEY)).isEmpty();
		assertThat(fixture.registry().counter(
			"home.search.nearby.place.cache.requests", "result", "error"
		).count()).isEqualTo(1);
	}

	@Test
	@DisplayName("empty result를 24시간 TTL로 저장하고 Redis write 장애는 요청을 실패시키지 않는다")
	void storesEmptyResultAndDegradesWriteFailure() {
		Fixture fixture = fixture();

		fixture.cache().store(KEY, EMPTY_RESULT);
		verify(fixture.values()).set(eq(KEY.redisKey()), any(String.class), eq(TTL));

		doThrow(new IllegalStateException("redis unavailable"))
			.when(fixture.values()).set(eq(KEY.redisKey()), any(String.class), eq(TTL));
		assertThatCode(() -> fixture.cache().store(KEY, EMPTY_RESULT)).doesNotThrowAnyException();
	}

	@SuppressWarnings("unchecked")
	private Fixture fixture() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(values);
		ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		return new Fixture(
			new RedisNearbyPlaceCache(redisTemplate, objectMapper, TTL, registry),
			redisTemplate,
			values,
			objectMapper,
			registry
		);
	}

	private record Fixture(
		RedisNearbyPlaceCache cache,
		StringRedisTemplate redisTemplate,
		ValueOperations<String, String> values,
		ObjectMapper objectMapper,
		SimpleMeterRegistry registry
	) {
	}
}
