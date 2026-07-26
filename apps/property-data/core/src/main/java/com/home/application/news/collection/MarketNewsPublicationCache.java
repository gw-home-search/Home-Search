package com.home.application.news.collection;

import com.home.domain.news.MarketNewsScopeType;

public interface MarketNewsPublicationCache {

    void publish(PublishedNewsSnapshot snapshot);

    void withdraw(MarketNewsScopeType scopeType, String regionCode, PublishedNewsSnapshot lastGood);
}
