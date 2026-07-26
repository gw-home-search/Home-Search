package com.home.domain.news;

import java.util.List;
import java.util.stream.Stream;

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

    public boolean isQualityChallenge() {
        if (nationwideDuplicateName) {
            return true;
        }
        return Stream.concat(Stream.of(canonicalName, tradeName), approvedAliases.stream())
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.toLowerCase().replaceAll("[^0-9a-z가-힣]", ""))
                .anyMatch(name -> !name.isBlank() && name.length() <= 4);
    }
}
