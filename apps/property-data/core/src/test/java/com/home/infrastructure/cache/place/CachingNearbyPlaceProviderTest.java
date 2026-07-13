package com.home.infrastructure.cache.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.home.application.place.NearbyPlacePoint;
import com.home.application.place.NearbyPlaceProvider;
import com.home.application.place.NearbyPlaceProviderResult;
import com.home.application.place.NearbyPlaceProviderUnavailableException;
import com.home.domain.place.NearbyPlaceCategory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CachingNearbyPlaceProviderTest {

	private static final NearbyPlacePoint CENTER = new NearbyPlacePoint(37.321, 127.109);
	private static final Instant NOW = Instant.parse("2026-07-13T03:00:00Z");

	@Test
	@DisplayName("같은 좌표 category의 24시간 cache hit는 quota와 Kakao 호출을 건너뛴다")
	void cacheHitSkipsQuotaAndProvider() {
		Map<NearbyPlaceCacheKey, NearbyPlaceProviderResult> values = new HashMap<>();
		NearbyPlaceCache cache = cache(values);
		AtomicInteger providerCalls = new AtomicInteger();
		AtomicInteger quotaCalls = new AtomicInteger();
		NearbyPlaceProvider delegate = (center, radius, category) -> {
			providerCalls.incrementAndGet();
			return new NearbyPlaceProviderResult(category, 0, NOW, List.of());
		};
		CachingNearbyPlaceProvider provider = new CachingNearbyPlaceProvider(
			delegate,
			cache,
			quotaCalls::incrementAndGet
		);

		provider.search(CENTER, 800, NearbyPlaceCategory.CAFE);
		provider.search(CENTER, 800, NearbyPlaceCategory.CAFE);

		assertThat(providerCalls).hasValue(1);
		assertThat(quotaCalls).hasValue(1);
		assertThat(values).hasSize(1);
	}

	@Test
	@DisplayName("quota guard가 실패하면 cache miss에서 Kakao를 호출하지 않는다")
	void quotaFailureIsFailClosed() {
		AtomicInteger providerCalls = new AtomicInteger();
		NearbyPlaceProvider delegate = (center, radius, category) -> {
			providerCalls.incrementAndGet();
			return new NearbyPlaceProviderResult(category, 0, NOW, List.of());
		};
		CachingNearbyPlaceProvider provider = new CachingNearbyPlaceProvider(
			delegate,
			cache(new HashMap<>()),
			() -> {
				throw new NearbyPlaceProviderUnavailableException("quota guard unavailable");
			}
		);

		assertThatThrownBy(() -> provider.search(CENTER, 800, NearbyPlaceCategory.CAFE))
			.isInstanceOf(NearbyPlaceProviderUnavailableException.class);
		assertThat(providerCalls).hasValue(0);
	}

	@Test
	@DisplayName("같은 key의 동시 cache miss는 Kakao를 한 번만 호출한다")
	void concurrentMissUsesSingleFlight() throws Exception {
		AtomicInteger providerCalls = new AtomicInteger();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		NearbyPlaceProvider delegate = (center, radius, category) -> {
			providerCalls.incrementAndGet();
			entered.countDown();
			try {
				release.await();
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(exception);
			}
			return new NearbyPlaceProviderResult(category, 0, NOW, List.of());
		};
		CachingNearbyPlaceProvider provider = new CachingNearbyPlaceProvider(
			delegate,
			cache(new HashMap<>()),
			() -> { }
		);

		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> provider.search(CENTER, 800, NearbyPlaceCategory.CAFE));
			entered.await();
			var second = executor.submit(() -> provider.search(CENTER, 800, NearbyPlaceCategory.CAFE));
			release.countDown();
			first.get();
			second.get();
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(providerCalls).hasValue(1);
	}

	private NearbyPlaceCache cache(Map<NearbyPlaceCacheKey, NearbyPlaceProviderResult> values) {
		return new NearbyPlaceCache() {
			@Override
			public Optional<NearbyPlaceProviderResult> find(NearbyPlaceCacheKey key) {
				return Optional.ofNullable(values.get(key));
			}

			@Override
			public void store(NearbyPlaceCacheKey key, NearbyPlaceProviderResult result) {
				values.put(key, result);
			}
		};
	}
}
