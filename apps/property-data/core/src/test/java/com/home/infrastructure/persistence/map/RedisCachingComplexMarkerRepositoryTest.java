package com.home.infrastructure.persistence.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.map.ComplexMarkerRepository;
import com.home.application.map.ComplexMarkerResult;
import com.home.application.map.ComplexMarkerQuery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisCachingComplexMarkerRepositoryTest {

	private static final Duration CACHE_TTL = Duration.ofSeconds(60);
	private static final String CACHE_METRIC_NAME = "home.search.map.marker.cache.requests";
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("Redis cache hit는 동일 marker 요청의 DB repository 조회를 건너뛴다")
	void cacheHitSkipsDelegateLookup() {
		var markers = List.of(new ComplexMarkerResult(1001L, 37.5123, 127.0456, 125000L, 740L));
		var delegate = new CountingComplexMarkerRepository(markers);
		var repository = new RedisCachingComplexMarkerRepository(
			delegate,
			redisTemplateBackedBy(new HashMap<>()),
			objectMapper,
			CACHE_TTL,
			new SimpleMeterRegistry()
		);

		assertThat(repository.findComplexMarkers(seedWideRequest())).isEqualTo(markers);
		assertThat(repository.findComplexMarkers(seedWideRequest())).isEqualTo(markers);

		assertThat(delegate.callCount).isEqualTo(1);
	}

	@Test
	@DisplayName("Redis cache key는 marker 필터가 다르면 별도 요청으로 취급한다")
	void cacheKeyIncludesMarkerFilters() {
		var seedWideMarkers = List.of(new ComplexMarkerResult(1001L, 37.5123, 127.0456, 125000L, 740L));
		var unitFilteredMarkers = List.of(new ComplexMarkerResult(2001L, 37.6010, 127.1010, 190000L, 900L));
		var delegate = new SequencedComplexMarkerRepository(seedWideMarkers, unitFilteredMarkers);
		var repository = new RedisCachingComplexMarkerRepository(
			delegate,
			redisTemplateBackedBy(new HashMap<>()),
			objectMapper,
			CACHE_TTL,
			new SimpleMeterRegistry()
		);

		assertThat(repository.findComplexMarkers(seedWideRequest())).isEqualTo(seedWideMarkers);
		assertThat(repository.findComplexMarkers(unitFilterRequest())).isEqualTo(unitFilteredMarkers);

		assertThat(delegate.callCount).isEqualTo(2);
	}

	@Test
	@DisplayName("Redis cache key는 bounds와 모든 marker 필터 조건을 구분한다")
	void cacheKeyIncludesBoundsAndEveryMarkerFilter() {
		var requests = List.of(
			seedWideRequest(),
			request(37.46, 126.85, 37.70, 127.20, null, null, null, null, null, null, null, null),
			request(37.45, 126.86, 37.70, 127.20, null, null, null, null, null, null, null, null),
			request(37.45, 126.85, 37.71, 127.20, null, null, null, null, null, null, null, null),
			request(37.45, 126.85, 37.70, 127.21, null, null, null, null, null, null, null, null),
			request(37.45, 126.85, 37.70, 127.20, 20, null, null, null, null, null, null, null),
			request(37.45, 126.85, 37.70, 127.20, null, 30, null, null, null, null, null, null),
			request(37.45, 126.85, 37.70, 127.20, null, null, 10.0, null, null, null, null, null),
			request(37.45, 126.85, 37.70, 127.20, null, null, null, 20.0, null, null, null, null),
			request(37.45, 126.85, 37.70, 127.20, null, null, null, null, 5, null, null, null),
			request(37.45, 126.85, 37.70, 127.20, null, null, null, null, null, 25, null, null),
			request(37.45, 126.85, 37.70, 127.20, null, null, null, null, null, null, 100L, null),
			request(37.45, 126.85, 37.70, 127.20, null, null, null, null, null, null, null, 2000L)
		);
		var responses = java.util.stream.LongStream.rangeClosed(1, requests.size())
			.mapToObj(RedisCachingComplexMarkerRepositoryTest::markers)
			.toList();
		var delegate = new SequencedComplexMarkerRepository(responses);
		var repository = new RedisCachingComplexMarkerRepository(
			delegate,
			redisTemplateBackedBy(new HashMap<>()),
			objectMapper,
			CACHE_TTL,
			new SimpleMeterRegistry()
		);

		for (int i = 0; i < requests.size(); i++) {
			assertThat(repository.findComplexMarkers(requests.get(i))).isEqualTo(responses.get(i));
		}
		for (int i = 0; i < requests.size(); i++) {
			assertThat(repository.findComplexMarkers(requests.get(i))).isEqualTo(responses.get(i));
		}

		assertThat(delegate.callCount).isEqualTo(requests.size());
	}

	@Test
	@DisplayName("Redis 장애는 marker API 실패로 전파하지 않고 DB repository 조회로 fallback한다")
	void redisFailureFallsBackToDelegateLookup() {
		var markers = List.of(new ComplexMarkerResult(1001L, 37.5123, 127.0456, 125000L, 740L));
		var delegate = new CountingComplexMarkerRepository(markers);
		var redisTemplate = mock(StringRedisTemplate.class);
		when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis unavailable"));
		var repository = new RedisCachingComplexMarkerRepository(
			delegate,
			redisTemplate,
			objectMapper,
			CACHE_TTL,
			new SimpleMeterRegistry()
		);

		assertThat(repository.findComplexMarkers(seedWideRequest())).isEqualTo(markers);

		assertThat(delegate.callCount).isEqualTo(1);
	}

	@Test
	@DisplayName("Redis cache는 hit miss fallback 결과를 metric으로 기록한다")
	void recordsCacheResultMetrics() {
		var markers = List.of(new ComplexMarkerResult(1001L, 37.5123, 127.0456, 125000L, 740L));
		var meterRegistry = new SimpleMeterRegistry();
		var healthyDelegate = new CountingComplexMarkerRepository(markers);
		var repository = new RedisCachingComplexMarkerRepository(
			healthyDelegate,
			redisTemplateBackedBy(new HashMap<>()),
			objectMapper,
			CACHE_TTL,
			meterRegistry
		);

		repository.findComplexMarkers(seedWideRequest());
		repository.findComplexMarkers(seedWideRequest());

		var fallbackDelegate = new CountingComplexMarkerRepository(markers);
		var brokenRedisTemplate = mock(StringRedisTemplate.class);
		when(brokenRedisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis unavailable"));
		var fallbackRepository = new RedisCachingComplexMarkerRepository(
			fallbackDelegate,
			brokenRedisTemplate,
			objectMapper,
			CACHE_TTL,
			meterRegistry
		);
		fallbackRepository.findComplexMarkers(seedWideRequest());

		assertThat(meterRegistry.counter(CACHE_METRIC_NAME, "endpoint", "complexes", "result", "miss").count())
			.isEqualTo(1);
		assertThat(meterRegistry.counter(CACHE_METRIC_NAME, "endpoint", "complexes", "result", "hit").count())
			.isEqualTo(1);
		assertThat(meterRegistry.counter(CACHE_METRIC_NAME, "endpoint", "complexes", "result", "fallback").count())
			.isEqualTo(1);
	}

	@Test
	@DisplayName("Redis cache write 실패는 fallback metric으로 기록하고 marker 응답은 유지한다")
	void recordsFallbackMetricWhenCacheWriteFails() {
		var markers = List.of(new ComplexMarkerResult(1001L, 37.5123, 127.0456, 125000L, 740L));
		var meterRegistry = new SimpleMeterRegistry();
		var delegate = new CountingComplexMarkerRepository(markers);
		var redisTemplate = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> operations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(operations);
		when(operations.get(anyString())).thenReturn(null);
		doThrow(new IllegalStateException("redis write failed"))
			.when(operations)
			.set(anyString(), anyString(), any(Duration.class));
		var repository = new RedisCachingComplexMarkerRepository(
			delegate,
			redisTemplate,
			objectMapper,
			CACHE_TTL,
			meterRegistry
		);

		assertThat(repository.findComplexMarkers(seedWideRequest())).isEqualTo(markers);

		assertThat(delegate.callCount).isEqualTo(1);
		assertThat(meterRegistry.counter(CACHE_METRIC_NAME, "endpoint", "complexes", "result", "fallback").count())
			.isEqualTo(1);
	}

	@SuppressWarnings("unchecked")
	private StringRedisTemplate redisTemplateBackedBy(Map<String, String> store) {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		ValueOperations<String, String> operations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(operations);
		when(operations.get(anyString())).thenAnswer(invocation -> store.get(invocation.getArgument(0)));
		doAnswer(invocation -> {
			store.put(invocation.getArgument(0), invocation.getArgument(1));
			return null;
		}).when(operations).set(anyString(), anyString(), any(Duration.class));
		return redisTemplate;
	}

	private static ComplexMarkerQuery seedWideRequest() {
		return request(
			37.45,
			126.85,
			37.70,
			127.20,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null
		);
	}

	private static ComplexMarkerQuery unitFilterRequest() {
		return request(
			37.45,
			126.85,
			37.70,
			127.20,
			null,
			null,
			null,
			null,
			null,
			null,
			100L,
			2000L
		);
	}

	private static ComplexMarkerQuery request(
		Double swLat,
		Double swLng,
		Double neLat,
		Double neLng,
		Integer pyeongMin,
		Integer pyeongMax,
		Double priceEokMin,
		Double priceEokMax,
		Integer ageMin,
		Integer ageMax,
		Long unitMin,
		Long unitMax
	) {
		return new ComplexMarkerQuery(
			swLat,
			swLng,
			neLat,
			neLng,
			pyeongMin,
			pyeongMax,
			priceEokMin,
			priceEokMax,
			ageMin,
			ageMax,
			unitMin,
			unitMax
		);
	}

	private static List<ComplexMarkerResult> markers(long parcelId) {
		return List.of(new ComplexMarkerResult(parcelId, 37.5123, 127.0456, 125000L, 740L));
	}

	private static class CountingComplexMarkerRepository implements ComplexMarkerRepository {

		private final List<ComplexMarkerResult> markers;
		private int callCount;

		CountingComplexMarkerRepository(List<ComplexMarkerResult> markers) {
			this.markers = markers;
		}

		@Override
		public List<ComplexMarkerResult> findComplexMarkers(ComplexMarkerQuery request) {
			callCount++;
			return markers;
		}
	}

	private static class SequencedComplexMarkerRepository implements ComplexMarkerRepository {

		private final List<List<ComplexMarkerResult>> responses;
		private int callCount;

		SequencedComplexMarkerRepository(List<List<ComplexMarkerResult>> responses) {
			this.responses = responses;
		}

		@SafeVarargs
		SequencedComplexMarkerRepository(List<ComplexMarkerResult>... responses) {
			this.responses = List.of(responses);
		}

		@Override
		public List<ComplexMarkerResult> findComplexMarkers(ComplexMarkerQuery request) {
			return responses.get(callCount++);
		}
	}
}
