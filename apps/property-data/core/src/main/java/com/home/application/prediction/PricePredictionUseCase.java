package com.home.application.prediction;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.stereotype.Service;

@Service
public class PricePredictionUseCase {

    private final PredictionBasisReader basisReader;
    private final PredictionFeatureSnapshotReader snapshotReader;
    private final PredictionFeatureAssembler featureAssembler;
    private final PredictionCacheRepository cacheRepository;
    private final PredictionClient client;
    private final Executor executor;
    private final Clock clock;
    private final PredictionProperties properties;

    public PricePredictionUseCase(
            PredictionBasisReader basisReader,
            PredictionFeatureSnapshotReader snapshotReader,
            PredictionFeatureAssembler featureAssembler,
            PredictionCacheRepository cacheRepository,
            PredictionClient client,
            PredictionExecutionContext executionContext,
            PredictionProperties properties) {
        this.basisReader = Objects.requireNonNull(basisReader);
        this.snapshotReader = Objects.requireNonNull(snapshotReader);
        this.featureAssembler = Objects.requireNonNull(featureAssembler);
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
        return basisReader
                .findBasis(complexId)
                .map(basis -> getOrSchedulePrediction(basis, anchorMonth))
                .orElseGet(() -> PricePredictionResult.unavailable(now(), "예측에 필요한 최근 거래가 부족합니다."));
    }

    private PricePredictionResult getOrSchedulePrediction(PredictionBasis basis, YearMonth anchorMonth) {
        PredictionCacheKey key = new PredictionCacheKey(basis.complexId(), basis.tradeId(), anchorMonth);
        return cacheRepository
                .find(key)
                .orElseGet(() -> snapshotReader
                        .readSnapshot(basis, anchorMonth)
                        .map(snapshot -> schedulePrediction(featureAssembler.assemble(basis, snapshot), key))
                        .orElseGet(() -> PricePredictionResult.unavailable(now(), "예측에 필요한 최근 거래가 부족합니다.")));
    }

    private PricePredictionResult schedulePrediction(PredictionFeature feature, PredictionCacheKey key) {
        PricePredictionResult pending = PricePredictionResult.pending(feature, properties, now());
        if (!cacheRepository.acquireLock(key, properties.lockTtl())) {
            return pending;
        }

        cacheRepository.save(key, pending, properties.pendingTtl());
        try {
            executor.execute(() -> runPrediction(feature, key));
        } catch (RejectedExecutionException exception) {
            PricePredictionResult failed =
                    PricePredictionResult.failed(feature, properties, now(), "AI prediction capacity unavailable.");
            cacheRepository.save(key, failed, properties.failedTtl());
            return failed;
        }
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
                    PricePredictionResult.failed(feature, properties, now(), failureMessage()),
                    properties.failedTtl());
        }
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private static String failureMessage() {
        return "AI prediction provider unavailable.";
    }
}
