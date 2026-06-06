package com.home.infrastructure.persistence.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.map.ComplexMarkerRepository;
import com.home.infrastructure.web.map.dto.ComplexMarkerResponse;
import com.home.infrastructure.web.map.dto.ComplexMarkersRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisCachingComplexMarkerRepositoryTest {

	private static final Duration CACHE_TTL = Duration.ofSeconds(60);
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("Redis cache hit는 동일 marker 요청의 DB repository 조회를 건너뛴다")
	void cacheHitSkipsDelegateLookup() {
		var markers = List.of(new ComplexMarkerResponse(1001L, 37.5123, 127.0456, 125000L, 740L));
		var delegate = new CountingComplexMarkerRepository(markers);
		var repository = new RedisCachingComplexMarkerRepository(
			delegate,
			redisTemplateBackedBy(new HashMap<>()),
			objectMapper,
			CACHE_TTL
		);

		assertThat(repository.findComplexMarkers(seedWideRequest())).isEqualTo(markers);
		assertThat(repository.findComplexMarkers(seedWideRequest())).isEqualTo(markers);

		assertThat(delegate.callCount).isEqualTo(1);
	}

	@Test
	@DisplayName("Redis cache key는 marker 필터가 다르면 별도 요청으로 취급한다")
	void cacheKeyIncludesMarkerFilters() {
		var seedWideMarkers = List.of(new ComplexMarkerResponse(1001L, 37.5123, 127.0456, 125000L, 740L));
		var unitFilteredMarkers = List.of(new ComplexMarkerResponse(2001L, 37.6010, 127.1010, 190000L, 900L));
		var delegate = new SequencedComplexMarkerRepository(seedWideMarkers, unitFilteredMarkers);
		var repository = new RedisCachingComplexMarkerRepository(
			delegate,
			redisTemplateBackedBy(new HashMap<>()),
			objectMapper,
			CACHE_TTL
		);

		assertThat(repository.findComplexMarkers(seedWideRequest())).isEqualTo(seedWideMarkers);
		assertThat(repository.findComplexMarkers(unitFilterRequest())).isEqualTo(unitFilteredMarkers);

		assertThat(delegate.callCount).isEqualTo(2);
	}

	@Test
	@DisplayName("Redis 장애는 marker API 실패로 전파하지 않고 DB repository 조회로 fallback한다")
	void redisFailureFallsBackToDelegateLookup() {
		var markers = List.of(new ComplexMarkerResponse(1001L, 37.5123, 127.0456, 125000L, 740L));
		var delegate = new CountingComplexMarkerRepository(markers);
		var redisTemplate = mock(StringRedisTemplate.class);
		when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis unavailable"));
		var repository = new RedisCachingComplexMarkerRepository(delegate, redisTemplate, objectMapper, CACHE_TTL);

		assertThat(repository.findComplexMarkers(seedWideRequest())).isEqualTo(markers);

		assertThat(delegate.callCount).isEqualTo(1);
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

	private static ComplexMarkersRequest seedWideRequest() {
		return new ComplexMarkersRequest(
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

	private static ComplexMarkersRequest unitFilterRequest() {
		return new ComplexMarkersRequest(
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

	private static class CountingComplexMarkerRepository implements ComplexMarkerRepository {

		private final List<ComplexMarkerResponse> markers;
		private int callCount;

		CountingComplexMarkerRepository(List<ComplexMarkerResponse> markers) {
			this.markers = markers;
		}

		@Override
		public List<ComplexMarkerResponse> findComplexMarkers(ComplexMarkersRequest request) {
			callCount++;
			return markers;
		}
	}

	private static class SequencedComplexMarkerRepository implements ComplexMarkerRepository {

		private final List<List<ComplexMarkerResponse>> responses;
		private int callCount;

		@SafeVarargs
		SequencedComplexMarkerRepository(List<ComplexMarkerResponse>... responses) {
			this.responses = List.of(responses);
		}

		@Override
		public List<ComplexMarkerResponse> findComplexMarkers(ComplexMarkersRequest request) {
			return responses.get(callCount++);
		}
	}
}
