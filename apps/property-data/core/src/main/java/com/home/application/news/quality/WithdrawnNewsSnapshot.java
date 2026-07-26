package com.home.application.news.quality;

import com.home.application.news.collection.PublishedNewsSnapshot;
import com.home.domain.news.MarketNewsScopeType;
import java.util.UUID;

public record WithdrawnNewsSnapshot(
        UUID snapshotId, MarketNewsScopeType scopeType, String regionCode, PublishedNewsSnapshot lastGood) {}
