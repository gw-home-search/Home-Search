package com.home.infrastructure.scheduling.coordinate;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("home.coordinate.readiness")
public record CoordinateReadinessProperties(
        @Positive @DefaultValue("500") int stageLimit,
        @Positive @DefaultValue("500") int resolveLimit,
        @Positive @DefaultValue("1000") int projectLimit) {}
