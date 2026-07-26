package com.home.application.news.collection;

import com.home.domain.news.MarketNewsCategory;
import com.home.domain.news.MarketNewsScopeType;
import com.home.domain.news.MarketNewsWorkUnitKind;
import com.home.domain.news.NewsComplexEvidence;
import java.util.List;
import java.util.UUID;

public record MarketNewsWorkUnitSpec(
        UUID workUnitId,
        int order,
        MarketNewsWorkUnitKind kind,
        MarketNewsScopeType scopeType,
        String regionCode,
        String regionName,
        MarketNewsCategory plannedCategory,
        String query,
        NewsComplexEvidence focusComplex,
        List<NewsComplexEvidence> matchingCorpus) {

    public MarketNewsWorkUnitSpec {
        matchingCorpus = matchingCorpus == null ? List.of() : List.copyOf(matchingCorpus);
    }
}
