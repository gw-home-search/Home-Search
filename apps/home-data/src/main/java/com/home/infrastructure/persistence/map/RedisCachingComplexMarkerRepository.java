package com.home.infrastructure.persistence.map;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.map.ComplexMarkerQuery;
import com.home.application.map.ComplexMarkerRepository;
import com.home.application.map.ComplexMarkerResult;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

final class RedisCachingComplexMarkerRepository implements ComplexMarkerRepository {

	private static final Logger log = LoggerFactory.getLogger(RedisCachingComplexMarkerRepository.class);
	private static final String CACHE_KEY_PREFIX = "home-search:map:complex:schema-a";
	static final String CACHE_REQUEST_METRIC_NAME = "home.search.map.marker.cache.requests";
	private static final TypeReference<List<ComplexMarkerResult>> MARKER_LIST_TYPE = new TypeReference<>() {
	};

	private final ComplexMarkerRepository delegate;
	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;
	private final Duration ttl;
	private final MeterRegistry meterRegistry;

	RedisCachingComplexMarkerRepository(
		ComplexMarkerRepository delegate,
		StringRedisTemplate redisTemplate,
		ObjectMapper objectMapper,
		Duration ttl,
		MeterRegistry meterRegistry
	) {
		this.delegate = Objects.requireNonNull(delegate);
		this.redisTemplate = Objects.requireNonNull(redisTemplate);
		this.objectMapper = Objects.requireNonNull(objectMapper);
		if (ttl == null || ttl.isZero() || ttl.isNegative()) {
			throw new IllegalArgumentException("Redis marker cache ttl must be positive");
		}
		this.ttl = ttl;
		this.meterRegistry = Objects.requireNonNull(meterRegistry);
	}

	@Override
	public List<ComplexMarkerResult> findComplexMarkers(ComplexMarkerQuery query) {
		String cacheKey = cacheKey(query);
		CacheLookup cachedMarkers = findCachedMarkers(cacheKey);
		if (cachedMarkers.status() == CacheLookupStatus.HIT) {
			recordCacheRequest("hit");
			return cachedMarkers.markers();
		}

		List<ComplexMarkerResult> markers = delegate.findComplexMarkers(query);
		boolean stored = storeMarkers(cacheKey, markers);
		if (cachedMarkers.status() == CacheLookupStatus.ERROR || !stored) {
			recordCacheRequest("fallback");
		} else {
			recordCacheRequest("miss");
		}
		return markers;
	}

	private CacheLookup findCachedMarkers(String cacheKey) {
		try {
			String cachedValue = redisTemplate.opsForValue().get(cacheKey);
			if (cachedValue == null || cachedValue.isBlank()) {
				return CacheLookup.miss();
			}
			return CacheLookup.hit(objectMapper.readValue(cachedValue, MARKER_LIST_TYPE));
		} catch (JsonProcessingException | RuntimeException ex) {
			log.debug("Failed to read Redis complex marker cache key={}", cacheKey, ex);
			return CacheLookup.error();
		}
	}

	private boolean storeMarkers(String cacheKey, List<ComplexMarkerResult> markers) {
		try {
			String serializedMarkers = objectMapper.writeValueAsString(markers);
			redisTemplate.opsForValue().set(cacheKey, serializedMarkers, ttl);
			return true;
		} catch (JsonProcessingException | RuntimeException ex) {
			log.debug("Failed to write Redis complex marker cache key={}", cacheKey, ex);
			return false;
		}
	}

	private void recordCacheRequest(String result) {
		Counter.builder(CACHE_REQUEST_METRIC_NAME)
			.description("map complex marker cache request counts")
			.tag("endpoint", "complexes")
			.tag("result", result)
			.register(meterRegistry)
			.increment();
	}

	private String cacheKey(ComplexMarkerQuery query) {
		return String.join("|",
			CACHE_KEY_PREFIX,
			"swLat=" + canonicalDouble(query.swLat()),
			"swLng=" + canonicalDouble(query.swLng()),
			"neLat=" + canonicalDouble(query.neLat()),
			"neLng=" + canonicalDouble(query.neLng()),
			"pyeongMin=" + canonicalValue(query.pyeongMin()),
			"pyeongMax=" + canonicalValue(query.pyeongMax()),
			"priceEokMin=" + canonicalDouble(query.priceEokMin()),
			"priceEokMax=" + canonicalDouble(query.priceEokMax()),
			"ageMin=" + canonicalValue(query.ageMin()),
			"ageMax=" + canonicalValue(query.ageMax()),
			"unitMin=" + canonicalValue(query.unitMin()),
			"unitMax=" + canonicalValue(query.unitMax())
		);
	}

	private static String canonicalDouble(Double value) {
		if (value == null) {
			return "_";
		}
		if (!Double.isFinite(value)) {
			return value.toString();
		}
		return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
	}

	private static String canonicalValue(Object value) {
		return value == null ? "_" : value.toString();
	}

	private record CacheLookup(CacheLookupStatus status, List<ComplexMarkerResult> markers) {

		private static CacheLookup hit(List<ComplexMarkerResult> markers) {
			return new CacheLookup(CacheLookupStatus.HIT, markers);
		}

		private static CacheLookup miss() {
			return new CacheLookup(CacheLookupStatus.MISS, List.of());
		}

		private static CacheLookup error() {
			return new CacheLookup(CacheLookupStatus.ERROR, List.of());
		}
	}

	private enum CacheLookupStatus {
		HIT,
		MISS,
		ERROR
	}
}
