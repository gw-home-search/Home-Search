package com.home.infrastructure.external.rtms;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("home.ingest.rtms")
public record RtmsIngestProperties(
        @Positive @DefaultValue("3") int refreshRetryAttempts,
        @PositiveOrZero @DefaultValue("250") long refreshRetryBackoffMillis,
        @DefaultValue("false") boolean allowCoordinatePendingOnly,
        @Valid @DefaultValue Daily daily) {

    public record Daily(
            @DefaultValue("") String lawdCds,
            @Positive @DefaultValue("2") int lookbackMonths) {}
}
