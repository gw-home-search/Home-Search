package com.home.application.news.read;

import com.home.domain.news.MarketNewsCategory;
import com.home.domain.news.MarketNewsDataStatus;
import com.home.domain.news.MarketNewsScopeType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MarketNewsReadResult(
        UUID snapshotId,
        Instant generatedAt,
        Instant dataCutoff,
        MarketNewsDataStatus dataStatus,
        MarketNewsScopeType scopeType,
        String regionCode,
        MarketNewsCategory category,
        List<MarketNewsItemView> items,
        String nextCursor) {

    public static MarketNewsReadResult unavailable(
            MarketNewsScopeType scopeType, String regionCode, MarketNewsCategory category) {
        return new MarketNewsReadResult(
                null, null, null, MarketNewsDataStatus.UNAVAILABLE, scopeType, regionCode, category, List.of(), null);
    }
}
