package com.home.infrastructure.cache.place;

import com.home.application.place.NearbyPlacePoint;
import com.home.application.place.NearbyPlaceProvider;
import com.home.application.place.NearbyPlaceProviderResult;
import com.home.domain.place.NearbyPlaceCategory;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

public final class CachingNearbyPlaceProvider implements NearbyPlaceProvider {

    private final NearbyPlaceProvider delegate;
    private final NearbyPlaceCache cache;
    private final NearbyPlaceQuotaGuard quotaGuard;
    private final ConcurrentHashMap<NearbyPlaceCacheKey, CompletableFuture<NearbyPlaceProviderResult>> inFlight =
            new ConcurrentHashMap<>();

    public CachingNearbyPlaceProvider(
            NearbyPlaceProvider delegate, NearbyPlaceCache cache, NearbyPlaceQuotaGuard quotaGuard) {
        this.delegate = Objects.requireNonNull(delegate);
        this.cache = Objects.requireNonNull(cache);
        this.quotaGuard = Objects.requireNonNull(quotaGuard);
    }

    @Override
    public NearbyPlaceProviderResult search(NearbyPlacePoint center, int radiusMeters, NearbyPlaceCategory category) {
        NearbyPlaceCacheKey key = NearbyPlaceCacheKey.from(center, radiusMeters, category);
        var cached = cache.find(key);
        if (cached.isPresent()) {
            return cached.get();
        }

        CompletableFuture<NearbyPlaceProviderResult> owner = new CompletableFuture<>();
        CompletableFuture<NearbyPlaceProviderResult> existing = inFlight.putIfAbsent(key, owner);
        if (existing != null) {
            return await(existing);
        }

        try {
            quotaGuard.acquire();
            NearbyPlaceProviderResult result = delegate.search(center, radiusMeters, category);
            cache.store(key, result);
            owner.complete(result);
            return result;
        } catch (RuntimeException exception) {
            owner.completeExceptionally(exception);
            throw exception;
        } finally {
            inFlight.remove(key, owner);
        }
    }

    private NearbyPlaceProviderResult await(CompletableFuture<NearbyPlaceProviderResult> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }
}
