package com.home.infrastructure.persistence.coordinate;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("home.coordinate.readiness")
public record CoordinateReadinessProperties(
        @Positive @DefaultValue("500") int stageLimit,
        @Positive @DefaultValue("500") int resolveLimit,
        @Positive @DefaultValue("1000") int projectLimit,
        @PositiveOrZero @DefaultValue("200") int retryLimit,

        @DurationUnit(ChronoUnit.MILLIS) @DefaultValue("21600000")
        Duration retryAfterMillis) {

    public CoordinateReadinessProperties {
        if (retryAfterMillis != null && retryAfterMillis.isNegative()) {
            throw new IllegalArgumentException("home.coordinate.readiness.retry-after-millis must not be negative");
        }
    }
}
