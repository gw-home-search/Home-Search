package com.home.infrastructure.web.news;

import com.home.application.news.read.MarketNewsReadResult;
import com.home.domain.news.MarketNewsCategory;
import com.home.domain.news.MarketNewsDataStatus;
import com.home.domain.news.MarketNewsScopeType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MarketNewsResponse(
        UUID snapshotId,
        Instant generatedAt,
        Instant dataCutoff,
        MarketNewsDataStatus dataStatus,
        ScopeResponse scope,
        MarketNewsCategory category,
        List<MarketNewsListItemResponse> items,
        String nextCursor) {

    static MarketNewsResponse from(MarketNewsReadResult result) {
        return new MarketNewsResponse(
                result.snapshotId(),
                result.generatedAt(),
                result.dataCutoff(),
                result.dataStatus(),
                new ScopeResponse(result.scopeType(), result.regionCode()),
                result.category(),
                result.items().stream().map(MarketNewsListItemResponse::from).toList(),
                result.nextCursor());
    }

    public record ScopeResponse(MarketNewsScopeType type, String regionCode) {}
}
