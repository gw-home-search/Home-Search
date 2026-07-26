package com.home.application.news.retention;

import java.time.Clock;
import java.util.Objects;

public class MarketNewsRetentionService {

    private final MarketNewsRetentionRepository repository;
    private final Clock clock;

    public MarketNewsRetentionService(MarketNewsRetentionRepository repository) {
        this(repository, Clock.systemUTC());
    }

    MarketNewsRetentionService(MarketNewsRetentionRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    public MarketNewsRetentionResult run() {
        return repository.deleteExpired(clock.instant());
    }
}
