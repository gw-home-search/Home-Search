package com.home.application.prediction;

import java.time.Duration;
import java.util.Optional;

public interface PredictionCacheRepository {

    Optional<PricePredictionResult> find(PredictionCacheKey key);

    boolean acquireLock(PredictionCacheKey key, Duration ttl);

    void save(PredictionCacheKey key, PricePredictionResult result, Duration ttl);
}
