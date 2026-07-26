package com.home.infrastructure.web.news;

import com.home.application.news.read.MarketNewsItemView;
import com.home.domain.news.MarketNewsCategory;
import com.home.domain.news.MarketNewsRelationType;
import java.time.Instant;

public record MarketNewsItemResponse(
        long articleId,
        MarketNewsCategory category,
        String title,
        Instant providedAt,
        String url,
        RegionResponse region,
        MarketNewsRelationType relationType) {

    static MarketNewsItemResponse from(MarketNewsItemView item) {
        RegionResponse region = item.regionCode() == null && item.regionName() == null
                ? null
                : new RegionResponse(item.regionCode(), item.regionName());
        return new MarketNewsItemResponse(
                item.articleId(),
                item.category(),
                item.title(),
                item.providedAt(),
                item.url(),
                region,
                item.relationType());
    }

    public record RegionResponse(String code, String name) {}
}
