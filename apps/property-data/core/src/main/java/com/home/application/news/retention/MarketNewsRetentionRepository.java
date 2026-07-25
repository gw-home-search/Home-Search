package com.home.application.news.retention;

import java.time.Instant;

public interface MarketNewsRetentionRepository {

    MarketNewsRetentionResult deleteExpired(Instant now);
}
