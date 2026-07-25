package com.home.infrastructure.web.news;

import com.home.application.news.read.MarketNewsItemView;
import com.home.domain.news.MarketNewsCategory;
import java.time.Instant;

public record MarketNewsListItemResponse(
        long articleId,
        MarketNewsCategory category,
        String title,
        Instant providedAt,
        String url,
        MarketNewsItemResponse.RegionResponse region) {

    static MarketNewsListItemResponse from(MarketNewsItemView item) {
        MarketNewsItemResponse.RegionResponse region = item.regionCode() == null && item.regionName() == null
                ? null
                : new MarketNewsItemResponse.RegionResponse(item.regionCode(), item.regionName());
        return new MarketNewsListItemResponse(
                item.articleId(), item.category(), item.title(), item.providedAt(), item.url(), region);
    }
}
