package com.home.application.news.quality;

import com.home.application.news.collection.MarketNewsPublicationCache;
import com.home.domain.news.MarketNewsWithdrawalReason;
import java.util.Objects;
import java.util.UUID;

public final class MarketNewsQualityService {

    private final MarketNewsQualityRepository repository;
    private final MarketNewsPublicationCache cache;

    public MarketNewsQualityService(MarketNewsQualityRepository repository, MarketNewsPublicationCache cache) {
        this.repository = Objects.requireNonNull(repository);
        this.cache = Objects.requireNonNull(cache);
    }

    public WithdrawnNewsSnapshot withdraw(UUID snapshotId, MarketNewsWithdrawalReason reason) {
        Objects.requireNonNull(snapshotId, "snapshotId is required");
        Objects.requireNonNull(reason, "reason is required");
        WithdrawnNewsSnapshot withdrawn = repository
                .withdrawPublished(snapshotId, reason)
                .orElseThrow(() -> new IllegalStateException("published news snapshot was not found"));
        cache.withdraw(withdrawn.scopeType(), withdrawn.regionCode(), withdrawn.lastGood());
        return withdrawn;
    }
}
