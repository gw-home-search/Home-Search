package com.home.application.prediction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PricePredictionUseCaseTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-25T07:05:38Z"), ZoneId.of("Asia/Seoul"));
    private static final PredictionProperties PROPERTIES = new PredictionProperties(
            true,
            "deployment__F37_monthly_anchor_prev3_rolling_huber_010",
            Duration.ofHours(24),
            Duration.ofSeconds(60),
            Duration.ofMinutes(10),
            Duration.ofHours(1),
            Duration.ofSeconds(60),
            new BigDecimal("0.188077"),
            "recent_holdout_p95",
            ZoneId.of("Asia/Seoul"));

    @Test
    @DisplayName("Redis READY hit이면 Python client를 호출하지 않는다")
    void redisReadyHitSkipsPythonClient() {
        FakeFeatureRepository featureRepository = new FakeFeatureRepository(feature());
        FakePredictionCacheRepository cacheRepository = new FakePredictionCacheRepository();
        cacheRepository.cached = Optional.of(readyResult());
        FakePredictionClient client = new FakePredictionClient();
        PricePredictionUseCase useCase = useCase(featureRepository, cacheRepository, client, new CapturingExecutor());

        PricePredictionResult result = useCase.getOrSchedulePrediction(501L);

        assertThat(result.status()).isEqualTo(PredictionStatus.READY);
        assertThat(result.predictedDealAmount()).isEqualTo(179163L);
        assertThat(client.callCount).isZero();
        assertThat(cacheRepository.lockAttempts).isZero();
    }

    @Test
    @DisplayName("cache miss이면 PENDING을 저장하고 async 예측 작업을 예약한다")
    void cacheMissStoresPendingAndSchedulesAsyncPrediction() {
        FakePredictionCacheRepository cacheRepository = new FakePredictionCacheRepository();
        CapturingExecutor executor = new CapturingExecutor();
        PricePredictionUseCase useCase =
                useCase(new FakeFeatureRepository(feature()), cacheRepository, new FakePredictionClient(), executor);

        PricePredictionResult result = useCase.getOrSchedulePrediction(501L);

        assertThat(result.status()).isEqualTo(PredictionStatus.PENDING);
        assertThat(cacheRepository.savedResults)
                .extracting(PricePredictionResult::status)
                .containsExactly(PredictionStatus.PENDING);
        assertThat(cacheRepository.savedTtls).containsExactly(Duration.ofSeconds(60));
        assertThat(executor.tasks).hasSize(1);
    }

    @Test
    @DisplayName("lock이 이미 있으면 중복 async 작업 없이 PENDING을 반환한다")
    void existingLockReturnsPendingWithoutDuplicateAsyncJob() {
        FakePredictionCacheRepository cacheRepository = new FakePredictionCacheRepository();
        cacheRepository.lockAcquired = false;
        CapturingExecutor executor = new CapturingExecutor();
        PricePredictionUseCase useCase =
                useCase(new FakeFeatureRepository(feature()), cacheRepository, new FakePredictionClient(), executor);

        PricePredictionResult result = useCase.getOrSchedulePrediction(501L);

        assertThat(result.status()).isEqualTo(PredictionStatus.PENDING);
        assertThat(cacheRepository.savedResults).isEmpty();
        assertThat(executor.tasks).isEmpty();
    }

    @Test
    @DisplayName("최근 거래 feature를 만들 수 없으면 UNAVAILABLE을 반환한다")
    void missingRecentTradeReturnsUnavailable() {
        PricePredictionUseCase useCase = useCase(
                new FakeFeatureRepository(null),
                new FakePredictionCacheRepository(),
                new FakePredictionClient(),
                new CapturingExecutor());

        PricePredictionResult result = useCase.getOrSchedulePrediction(501L);

        assertThat(result.status()).isEqualTo(PredictionStatus.UNAVAILABLE);
        assertThat(result.message()).contains("최근 거래");
    }

    @Test
    @DisplayName("Python 호출 실패는 detail API로 전파하지 않도록 FAILED cache를 저장한다")
    void pythonFailureStoresFailedCache() {
        FakePredictionCacheRepository cacheRepository = new FakePredictionCacheRepository();
        FakePredictionClient client = new FakePredictionClient();
        client.failure = new IllegalStateException("ml timeout");
        CapturingExecutor executor = new CapturingExecutor();
        PricePredictionUseCase useCase =
                useCase(new FakeFeatureRepository(feature()), cacheRepository, client, executor);

        assertThat(useCase.getOrSchedulePrediction(501L).status()).isEqualTo(PredictionStatus.PENDING);
        executor.runAll();

        assertThat(cacheRepository.savedResults)
                .extracting(PricePredictionResult::status)
                .containsExactly(PredictionStatus.PENDING, PredictionStatus.FAILED);
        assertThat(cacheRepository.savedTtls).containsExactly(Duration.ofSeconds(60), Duration.ofMinutes(10));
        assertThat(cacheRepository.savedResults.get(1).message())
                .isEqualTo("AI prediction provider unavailable.")
                .doesNotContain("ml timeout");
    }

    @Test
    @DisplayName("prediction executor가 포화되면 PENDING을 남기지 않고 FAILED로 확정한다")
    void saturatedExecutorStoresAndReturnsFailedPrediction() {
        FakePredictionCacheRepository cacheRepository = new FakePredictionCacheRepository();
        Executor saturatedExecutor = command -> {
            throw new RejectedExecutionException("executor saturated");
        };
        PricePredictionUseCase useCase = useCase(
                new FakeFeatureRepository(feature()), cacheRepository, new FakePredictionClient(), saturatedExecutor);

        PricePredictionResult result = useCase.getOrSchedulePrediction(501L);

        assertThat(result.status()).isEqualTo(PredictionStatus.FAILED);
        assertThat(result.message()).doesNotContain("executor saturated");
        assertThat(cacheRepository.savedResults)
                .extracting(PricePredictionResult::status)
                .containsExactly(PredictionStatus.PENDING, PredictionStatus.FAILED);
        assertThat(cacheRepository.savedTtls).containsExactly(Duration.ofSeconds(60), Duration.ofMinutes(10));
    }

    private PricePredictionUseCase useCase(
            PredictionFeatureRepository featureRepository,
            FakePredictionCacheRepository cacheRepository,
            PredictionClient client,
            Executor executor) {
        return new PricePredictionUseCase(
                featureRepository,
                cacheRepository,
                client,
                new PredictionExecutionContext(executor, CLOCK),
                PROPERTIES);
    }

    private static PredictionFeature feature() {
        Map<String, Object> numeric = new HashMap<>();
        numeric.put("area_m2", 84.69);
        numeric.put("floor", 6);
        numeric.put("log_complex_prev_price_per_m2", 7.62);
        return new PredictionFeature(
                501L,
                9001L,
                LocalDate.of(2026, 1, 1),
                new BigDecimal("84.69"),
                6,
                numeric,
                Map.of(
                        "legal_dong_code", "1168010300",
                        "sgg_code", "11680",
                        "prev_deal_gap_bucket", "91-180"),
                7.62);
    }

    private static PricePredictionResult readyResult() {
        return new PricePredictionResult(
                PredictionStatus.READY,
                "deployment__F37_monthly_anchor_prev3_rolling_huber_010",
                179163L,
                new BigDecimal("2115.5"),
                new BigDecimal("6993.4"),
                139425L,
                218900L,
                "recent_holdout_p95",
                new BigDecimal("84.69"),
                6,
                9001L,
                LocalDate.of(2026, 1, 1),
                Instant.parse("2026-06-25T07:05:38Z"),
                null);
    }

    private static class FakeFeatureRepository implements PredictionFeatureRepository {

        private final PredictionFeature feature;

        FakeFeatureRepository(PredictionFeature feature) {
            this.feature = feature;
        }

        @Override
        public Optional<PredictionFeature> findFeature(Long complexId, YearMonth anchorMonth) {
            return Optional.ofNullable(feature);
        }
    }

    private static class FakePredictionCacheRepository implements PredictionCacheRepository {

        private Optional<PricePredictionResult> cached = Optional.empty();
        private boolean lockAcquired = true;
        private int lockAttempts;
        private final List<PricePredictionResult> savedResults = new ArrayList<>();
        private final List<Duration> savedTtls = new ArrayList<>();

        @Override
        public Optional<PricePredictionResult> find(PredictionCacheKey key) {
            return cached;
        }

        @Override
        public boolean acquireLock(PredictionCacheKey key, Duration ttl) {
            lockAttempts++;
            return lockAcquired;
        }

        @Override
        public void save(PredictionCacheKey key, PricePredictionResult result, Duration ttl) {
            savedResults.add(result);
            savedTtls.add(ttl);
        }
    }

    private static class FakePredictionClient implements PredictionClient {

        private int callCount;
        private RuntimeException failure;

        @Override
        public PredictionClientResult predict(PredictionRequest request) {
            callCount++;
            if (failure != null) {
                throw failure;
            }
            return new PredictionClientResult(
                    "deployment__F37_monthly_anchor_prev3_rolling_huber_010",
                    new BigDecimal("2115.5"),
                    179163L,
                    new BigDecimal("6993.4"),
                    new BigDecimal("0.01"),
                    new BigDecimal("7.65"),
                    139425L,
                    218900L,
                    "recent_holdout_p95");
        }
    }

    private static class CapturingExecutor implements Executor {

        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runAll() {
            tasks.forEach(Runnable::run);
        }
    }
}
