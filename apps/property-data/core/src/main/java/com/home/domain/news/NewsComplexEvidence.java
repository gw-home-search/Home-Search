package com.home.domain.news;

import java.util.List;

public record NewsComplexEvidence(
        long complexId,
        String canonicalName,
        String tradeName,
        List<String> approvedAliases,
        NewsRegionEvidence region,
        boolean nationwideDuplicateName) {

    public NewsComplexEvidence {
        approvedAliases = approvedAliases == null ? List.of() : List.copyOf(approvedAliases);
    }
}
