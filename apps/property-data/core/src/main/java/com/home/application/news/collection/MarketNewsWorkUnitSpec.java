package com.home.application.news.collection;

import com.home.domain.news.MarketNewsCategory;
import com.home.domain.news.MarketNewsScopeType;
import com.home.domain.news.MarketNewsWorkUnitKind;
import com.home.domain.news.NewsComplexEvidence;
import java.time.Instant;
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
        List<NewsComplexEvidence> matchingCorpus,
        int nextProviderStart,
        int collectedCallCount,
        int collectedRawItemCount,
        Instant oldestProvidedAt) {

    public MarketNewsWorkUnitSpec {
        matchingCorpus = matchingCorpus == null ? List.of() : List.copyOf(matchingCorpus);
        if (nextProviderStart < 1 || nextProviderStart > 1001 || (nextProviderStart - 1) % 100 != 0) {
            throw new IllegalArgumentException("nextProviderStart must follow provider pagination");
        }
        if (collectedCallCount < 0 || collectedRawItemCount < 0) {
            throw new IllegalArgumentException("collected counts must not be negative");
        }
    }

    public MarketNewsWorkUnitSpec(
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
        this(
                workUnitId,
                order,
                kind,
                scopeType,
                regionCode,
                regionName,
                plannedCategory,
                query,
                focusComplex,
                matchingCorpus,
                1,
                0,
                0,
                null);
    }
}
