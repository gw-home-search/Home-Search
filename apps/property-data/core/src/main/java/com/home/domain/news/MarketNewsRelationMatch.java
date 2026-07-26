package com.home.domain.news;

import java.util.List;

public record MarketNewsRelationMatch(
        MarketNewsRelationType relationType, String regionCode, Long complexId, List<String> matchedTokens) {}
