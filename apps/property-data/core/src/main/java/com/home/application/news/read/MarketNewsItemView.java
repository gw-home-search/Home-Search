package com.home.application.news.read;

import com.home.domain.news.MarketNewsCategory;
import com.home.domain.news.MarketNewsRelationType;
import java.time.Instant;

public record MarketNewsItemView(
        long articleId,
        MarketNewsCategory category,
        String title,
        Instant providedAt,
        String url,
        String regionCode,
        String regionName,
        MarketNewsRelationType relationType) {}
