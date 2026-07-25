package com.home.application.news.collection;

import java.time.Instant;

public record NormalizedNewsItem(
        String title,
        String description,
        String publicUrl,
        String canonicalUrlHash,
        Instant providedAt,
        int providerStart,
        int providerRank) {}
