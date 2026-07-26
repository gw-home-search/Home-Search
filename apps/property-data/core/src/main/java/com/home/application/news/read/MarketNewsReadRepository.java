package com.home.application.news.read;

import com.home.domain.news.MarketNewsCategory;
import com.home.domain.news.MarketNewsScopeType;
import java.util.List;
import java.util.Optional;

public interface MarketNewsReadRepository {

    boolean existsRootSidoCode(String regionCode);

    boolean existsComplex(long complexId);

    Optional<MarketNewsReadResult> findPublished(
            MarketNewsScopeType scopeType,
            String regionCode,
            MarketNewsCategory category,
            MarketNewsCursor cursor,
            int limit);

    List<MarketNewsItemView> findComplexNews(long complexId, int limit);
}
