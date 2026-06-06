package com.home.infrastructure.persistence.map;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.map.ComplexMarkerRepository;
import com.home.infrastructure.web.map.dto.ComplexMarkerResponse;
import com.home.infrastructure.web.map.dto.ComplexMarkersRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

final class RedisCachingComplexMarkerRepository implements ComplexMarkerRepository {

	private static final Logger log = LoggerFactory.getLogger(RedisCachingComplexMarkerRepository.class);
	private static final String CACHE_KEY_PREFIX = "home-search:map:complex:schema-a";
	private static final TypeReference<List<ComplexMarkerResponse>> MARKER_LIST_TYPE = new TypeReference<>() {
	};

	private final ComplexMarkerRepository delegate;
	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;
	private final Duration ttl;

	RedisCachingComplexMarkerRepository(
		ComplexMarkerRepository delegate,
		StringRedisTemplate redisTemplate,
		ObjectMapper objectMapper,
		Duration ttl
	) {
		this.delegate = Objects.requireNonNull(delegate);
		this.redisTemplate = Objects.requireNonNull(redisTemplate);
		this.objectMapper = Objects.requireNonNull(objectMapper);
		if (ttl == null || ttl.isZero() || ttl.isNegative()) {
			throw new IllegalArgumentException("Redis marker cache ttl must be positive");
		}
		this.ttl = ttl;
	}

	@Override
	public List<ComplexMarkerResponse> findComplexMarkers(ComplexMarkersRequest request) {
		String cacheKey = cacheKey(request);
		Optional<List<ComplexMarkerResponse>> cachedMarkers = findCachedMarkers(cacheKey);
		if (cachedMarkers.isPresent()) {
			return cachedMarkers.get();
		}

		List<ComplexMarkerResponse> markers = delegate.findComplexMarkers(request);
		storeMarkers(cacheKey, markers);
		return markers;
	}

	private Optional<List<ComplexMarkerResponse>> findCachedMarkers(String cacheKey) {
		try {
			String cachedValue = redisTemplate.opsForValue().get(cacheKey);
			if (cachedValue == null || cachedValue.isBlank()) {
				return Optional.empty();
			}
			return Optional.of(objectMapper.readValue(cachedValue, MARKER_LIST_TYPE));
		} catch (JsonProcessingException | RuntimeException ex) {
			log.debug("Failed to read Redis complex marker cache key={}", cacheKey, ex);
			return Optional.empty();
		}
	}

	private void storeMarkers(String cacheKey, List<ComplexMarkerResponse> markers) {
		try {
			String serializedMarkers = objectMapper.writeValueAsString(markers);
			redisTemplate.opsForValue().set(cacheKey, serializedMarkers, ttl);
		} catch (JsonProcessingException | RuntimeException ex) {
			log.debug("Failed to write Redis complex marker cache key={}", cacheKey, ex);
		}
	}

	private String cacheKey(ComplexMarkersRequest request) {
		return String.join("|",
			CACHE_KEY_PREFIX,
			"swLat=" + canonicalDouble(request.swLat()),
			"swLng=" + canonicalDouble(request.swLng()),
			"neLat=" + canonicalDouble(request.neLat()),
			"neLng=" + canonicalDouble(request.neLng()),
			"pyeongMin=" + canonicalValue(request.pyeongMin()),
			"pyeongMax=" + canonicalValue(request.pyeongMax()),
			"priceEokMin=" + canonicalDouble(request.priceEokMin()),
			"priceEokMax=" + canonicalDouble(request.priceEokMax()),
			"ageMin=" + canonicalValue(request.ageMin()),
			"ageMax=" + canonicalValue(request.ageMax()),
			"unitMin=" + canonicalValue(request.unitMin()),
			"unitMax=" + canonicalValue(request.unitMax())
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
}
