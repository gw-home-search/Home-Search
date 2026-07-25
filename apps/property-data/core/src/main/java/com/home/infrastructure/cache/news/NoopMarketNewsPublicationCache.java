package com.home.infrastructure.cache.news;

import com.home.application.news.collection.MarketNewsPublicationCache;
import com.home.application.news.collection.PublishedNewsSnapshot;
import com.home.domain.news.MarketNewsScopeType;

public final class NoopMarketNewsPublicationCache implements MarketNewsPublicationCache {

    @Override
    public void publish(PublishedNewsSnapshot snapshot) {
        // PostgreSQL remains authoritative when Redis publication caching is disabled.
    }

    @Override
    public void withdraw(MarketNewsScopeType scopeType, String regionCode, PublishedNewsSnapshot lastGood) {
        // PostgreSQL remains authoritative when Redis publication caching is disabled.
    }
}
