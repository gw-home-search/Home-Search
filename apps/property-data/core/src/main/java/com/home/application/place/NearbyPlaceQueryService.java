package com.home.application.place;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.home.application.read.ResourceNotFoundException;
import com.home.domain.place.NearbyPlaceCategory;

public class NearbyPlaceQueryService implements NearbyPlaceUseCase {

	private static final int DEFAULT_RADIUS_METERS = 800;
	private static final int MIN_RADIUS_METERS = 100;
	private static final int MAX_RADIUS_METERS = 2_000;
	private static final int DEFAULT_LIMIT_PER_CATEGORY = 5;
	private static final int MAX_LIMIT_PER_CATEGORY = 15;
	private static final List<NearbyPlaceCategory> DEFAULT_CATEGORIES = List.of(NearbyPlaceCategory.values());

	private final NearbyPlaceCenterReader centerReader;
	private final NearbyPlaceProvider provider;
	private final Executor executor;
	private final Clock clock;
	private final long timeoutMillis;

	public NearbyPlaceQueryService(
		NearbyPlaceCenterReader centerReader,
		NearbyPlaceProvider provider,
		Executor executor,
		Clock clock,
		long timeoutMillis
	) {
		this.centerReader = Objects.requireNonNull(centerReader);
		this.provider = Objects.requireNonNull(provider);
		this.executor = Objects.requireNonNull(executor);
		this.clock = Objects.requireNonNull(clock);
		if (timeoutMillis < 1) {
			throw new IllegalArgumentException("nearby place timeout must be positive");
		}
		this.timeoutMillis = timeoutMillis;
	}

	@Override
	public NearbyPlacesResult getNearbyPlaces(
		Long complexId,
		Integer requestedRadiusMeters,
		List<NearbyPlaceCategory> requestedCategories,
		Integer requestedLimitPerCategory
	) {
		if (complexId == null || complexId < 1) {
			throw new InvalidNearbyPlaceRequestException("complexId must be positive");
		}
		int radiusMeters = normalizeRadius(requestedRadiusMeters);
		int limitPerCategory = normalizeLimit(requestedLimitPerCategory);
		List<NearbyPlaceCategory> categories = normalizeCategories(requestedCategories);
		NearbyPlaceCenter center = centerReader.findComplexCenter(complexId)
			.orElseThrow(() -> new ResourceNotFoundException("complex not found: " + complexId));
		NearbyPlacePoint point = requiredPoint(center);

		List<CompletableFuture<NearbyPlaceProviderResult>> futures = categories.stream()
			.map(category -> CompletableFuture.supplyAsync(
				() -> provider.search(point, radiusMeters, category),
				executor
			))
			.toList();

		waitForAll(futures);
		List<NearbyPlaceCategoryResult> results = new ArrayList<>(futures.size());
		for (int index = 0; index < futures.size(); index++) {
			NearbyPlaceProviderResult providerResult = join(futures.get(index));
			NearbyPlaceCategory expectedCategory = categories.get(index);
			if (providerResult == null || providerResult.category() != expectedCategory) {
				throw new NearbyPlaceProviderUnavailableException("nearby place provider category mismatch");
			}
			List<NearbyPlaceItem> places = providerResult.places() == null
				? List.of()
				: providerResult.places().stream()
					.sorted(Comparator.comparingInt(NearbyPlaceItem::distanceMeters))
					.limit(limitPerCategory)
					.toList();
			int matchedCount = Math.max(0, providerResult.matchedCount());
			results.add(new NearbyPlaceCategoryResult(
				expectedCategory,
				matchedCount,
				places.size(),
				matchedCount > places.size(),
				Objects.requireNonNull(providerResult.retrievedAt()),
				places
			));
		}

		return new NearbyPlacesResult(complexId, point, radiusMeters, clock.instant(), List.copyOf(results));
	}

	private int normalizeRadius(Integer requestedRadiusMeters) {
		int radiusMeters = requestedRadiusMeters == null ? DEFAULT_RADIUS_METERS : requestedRadiusMeters;
		if (radiusMeters < MIN_RADIUS_METERS || radiusMeters > MAX_RADIUS_METERS) {
			throw new InvalidNearbyPlaceRequestException("radiusMeters must be between 100 and 2000");
		}
		return radiusMeters;
	}

	private int normalizeLimit(Integer requestedLimitPerCategory) {
		int limit = requestedLimitPerCategory == null ? DEFAULT_LIMIT_PER_CATEGORY : requestedLimitPerCategory;
		if (limit < 1 || limit > MAX_LIMIT_PER_CATEGORY) {
			throw new InvalidNearbyPlaceRequestException("limitPerCategory must be between 1 and 15");
		}
		return limit;
	}

	private List<NearbyPlaceCategory> normalizeCategories(List<NearbyPlaceCategory> requestedCategories) {
		if (requestedCategories == null || requestedCategories.isEmpty()) {
			return DEFAULT_CATEGORIES;
		}
		LinkedHashSet<NearbyPlaceCategory> requested = new LinkedHashSet<>(requestedCategories);
		return DEFAULT_CATEGORIES.stream().filter(requested::contains).toList();
	}

	private NearbyPlacePoint requiredPoint(NearbyPlaceCenter center) {
		if (center.lat() == null || center.lng() == null
			|| !Double.isFinite(center.lat()) || !Double.isFinite(center.lng())
			|| center.lat() < -90 || center.lat() > 90
			|| center.lng() < -180 || center.lng() > 180) {
			throw new NearbyPlaceCenterUnavailableException("complex display coordinate unavailable");
		}
		return new NearbyPlacePoint(center.lat(), center.lng());
	}

	private void waitForAll(List<CompletableFuture<NearbyPlaceProviderResult>> futures) {
		try {
			CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
				.get(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			cancel(futures);
			throw new NearbyPlaceProviderUnavailableException("nearby place query interrupted", exception);
		} catch (TimeoutException exception) {
			cancel(futures);
			throw new NearbyPlaceProviderUnavailableException("nearby place query timed out", exception);
		} catch (ExecutionException exception) {
			cancel(futures);
			throw providerFailure(exception.getCause());
		}
	}

	private NearbyPlaceProviderResult join(CompletableFuture<NearbyPlaceProviderResult> future) {
		try {
			return future.join();
		} catch (RuntimeException exception) {
			throw providerFailure(exception.getCause() == null ? exception : exception.getCause());
		}
	}

	private NearbyPlaceProviderUnavailableException providerFailure(Throwable cause) {
		if (cause instanceof NearbyPlaceProviderUnavailableException unavailable) {
			return unavailable;
		}
		return new NearbyPlaceProviderUnavailableException("nearby place provider unavailable", cause);
	}

	private void cancel(List<CompletableFuture<NearbyPlaceProviderResult>> futures) {
		futures.forEach(future -> future.cancel(true));
	}
}
