package com.home.application.prediction;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.springframework.stereotype.Service;

@Service
public class PricePredictionUseCase {

    private final PredictionFeatureRepository featureRepository;
    private final PredictionCacheRepository cacheRepository;
    private final PredictionClient client;
    private final Executor executor;
    private final Clock clock;
    private final PredictionProperties properties;

    public PricePredictionUseCase(
            PredictionFeatureRepository featureRepository,
            PredictionCacheRepository cacheRepository,
            PredictionClient client,
            PredictionExecutionContext executionContext,
            PredictionProperties properties) {
        this.featureRepository = Objects.requireNonNull(featureRepository);
        this.cacheRepository = Objects.requireNonNull(cacheRepository);
        this.client = Objects.requireNonNull(client);
        PredictionExecutionContext context = Objects.requireNonNull(executionContext);
        this.executor = context.executor();
        this.clock = context.clock();
        this.properties = Objects.requireNonNull(properties);
    }

    public PricePredictionResult getOrSchedulePrediction(Long complexId) {
        if (!properties.enabled()) {
            return PricePredictionResult.unavailable(now(), "AI prediction is disabled.");
        }
        if (complexId == null) {
            return PricePredictionResult.unavailable(now(), "예측에 필요한 complexId가 없습니다.");
        }

        YearMonth anchorMonth = YearMonth.now(clock.withZone(properties.zoneId()));
        return featureRepository
                .findFeature(complexId, anchorMonth)
                .map(feature -> getOrSchedulePrediction(feature, anchorMonth))
                .orElseGet(() -> PricePredictionResult.unavailable(now(), "예측에 필요한 최근 거래가 부족합니다."));
    }

    private PricePredictionResult getOrSchedulePrediction(PredictionFeature feature, YearMonth anchorMonth) {
        PredictionCacheKey key = new PredictionCacheKey(feature.complexId(), feature.basisTradeId(), anchorMonth);
        return cacheRepository.find(key).orElseGet(() -> schedulePrediction(feature, key));
    }

    private PricePredictionResult schedulePrediction(PredictionFeature feature, PredictionCacheKey key) {
        PricePredictionResult pending = PricePredictionResult.pending(feature, properties, now());
        if (!cacheRepository.acquireLock(key, properties.lockTtl())) {
            return pending;
        }

        cacheRepository.save(key, pending, properties.pendingTtl());
        executor.execute(() -> runPrediction(feature, key));
        return pending;
    }

    private void runPrediction(PredictionFeature feature, PredictionCacheKey key) {
        try {
            PredictionRequest request = new PredictionRequest(
                    feature.numericFeatures(),
                    feature.embeddingFeatures(),
                    feature.baseLogValue(),
                    feature.targetAreaM2(),
                    properties.intervalPct(),
                    properties.intervalBasis());
            PredictionClientResult clientResult = client.predict(request);
            cacheRepository.save(
                    key, PricePredictionResult.ready(feature, properties, clientResult, now()), properties.readyTtl());
        } catch (RuntimeException ex) {
            cacheRepository.save(
                    key,
                    PricePredictionResult.failed(feature, properties, now(), failureMessage(ex)),
                    properties.failedTtl());
        }
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private static String failureMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
