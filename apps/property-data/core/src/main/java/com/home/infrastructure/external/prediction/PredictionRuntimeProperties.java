package com.home.infrastructure.external.prediction;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("home.prediction")
public record PredictionRuntimeProperties(
        @DefaultValue("true") boolean enabled,

        @NotBlank @DefaultValue("deployment__F37_monthly_anchor_prev3_rolling_huber_010")
        String modelVersion,

        @Valid @DefaultValue Cache cache,
        @Valid @DefaultValue Interval interval,
        @NotNull @DefaultValue("Asia/Seoul") ZoneId zone,
        @Valid @DefaultValue Executor executor,
        @Valid @DefaultValue Client client) {

    public record Cache(
            @NotNull @DefaultValue("24h") Duration ttl,
            @NotNull @DefaultValue("60s") Duration pendingTtl,
            @NotNull @DefaultValue("10m") Duration failedTtl,
            @NotNull @DefaultValue("1h") Duration unavailableTtl,
            @NotNull @DefaultValue("60s") Duration lockTtl) {

        public Cache {
            requirePositive(ttl, "home.prediction.cache.ttl");
            requirePositive(pendingTtl, "home.prediction.cache.pending-ttl");
            requirePositive(failedTtl, "home.prediction.cache.failed-ttl");
            requirePositive(unavailableTtl, "home.prediction.cache.unavailable-ttl");
            requirePositive(lockTtl, "home.prediction.cache.lock-ttl");
        }
    }

    public record Interval(
            @NotNull @DefaultValue("0.188077") BigDecimal pct,
            @NotBlank @DefaultValue("recent_holdout_p95") String basis) {

        public Interval {
            if (pct != null && (pct.signum() <= 0 || pct.compareTo(BigDecimal.ONE) >= 0)) {
                throw new IllegalArgumentException("home.prediction.interval.pct must be between 0 and 1");
            }
        }
    }

    public record Executor(
            @Min(1) @Max(16) @DefaultValue("2") int threads) {}

    public record Client(
            @NotNull @DefaultValue("http://localhost:8001") URI baseUrl,

            @NotNull @DurationUnit(ChronoUnit.MILLIS) @DefaultValue("1000")
            Duration connectTimeoutMillis,

            @NotNull @DurationUnit(ChronoUnit.MILLIS) @DefaultValue("3000")
            Duration readTimeoutMillis) {

        public Client {
            requirePositive(connectTimeoutMillis, "home.prediction.client.connect-timeout-millis");
            requirePositive(readTimeoutMillis, "home.prediction.client.read-timeout-millis");
        }
    }

    private static void requirePositive(Duration duration, String property) {
        if (duration != null && (duration.isZero() || duration.isNegative())) {
            throw new IllegalArgumentException(property + " must be positive");
        }
    }
}
