package com.home.application.news.collection;

import com.home.domain.news.MarketNewsScopeType;
import java.time.Instant;
import java.util.UUID;

public record PublishedNewsSnapshot(
        UUID snapshotId, MarketNewsScopeType scopeType, String regionCode, Instant generatedAt, Instant dataCutoff) {}
