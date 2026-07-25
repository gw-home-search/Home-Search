package com.home.domain.news;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MarketNewsRelationPolicy {

    public List<MarketNewsRelationMatch> match(String title, String description, List<NewsComplexEvidence> complexes) {
        String text = normalize(title + " " + description);
        List<NewsComplexEvidence> ordered = complexes == null
                ? List.of()
                : complexes.stream()
                        .sorted(Comparator.comparingInt(this::longestNameLength)
                                .reversed()
                                .thenComparingLong(NewsComplexEvidence::complexId))
                        .toList();
        List<MarketNewsRelationMatch> matches = new ArrayList<>();
        Set<String> regionRelations = new LinkedHashSet<>();
        for (NewsComplexEvidence complex : ordered) {
            NewsRegionEvidence region = complex.region();
            if (region == null) {
                continue;
            }
            String matchedName = matchingName(text, complex);
            boolean sigungu = contains(text, region.sigunguName());
            boolean dong = contains(text, region.dongName());
            boolean sido = contains(text, region.sidoName());
            if (matchedName != null && sigungu && (!requiresDong(complex, matchedName) || dong)) {
                matches.add(new MarketNewsRelationMatch(
                        MarketNewsRelationType.DIRECT_COMPLEX,
                        region.sidoCode(),
                        complex.complexId(),
                        compactTokens(matchedName, region.sigunguName(), dong ? region.dongName() : null)));
                continue;
            }
            if (sigungu && dong && regionRelations.add("D:" + region.dongCode())) {
                matches.add(new MarketNewsRelationMatch(
                        MarketNewsRelationType.SAME_DONG,
                        region.dongCode(),
                        null,
                        compactTokens(region.sigunguName(), region.dongName())));
            } else if (sido && sigungu && regionRelations.add("S:" + region.sigunguCode())) {
                matches.add(new MarketNewsRelationMatch(
                        MarketNewsRelationType.SAME_SIGUNGU,
                        region.sigunguCode(),
                        null,
                        compactTokens(region.sidoName(), region.sigunguName())));
            }
        }
        return List.copyOf(matches);
    }

    private boolean requiresDong(NewsComplexEvidence complex, String matchedName) {
        return complex.nationwideDuplicateName() || normalizedLength(matchedName) <= 4;
    }

    private String matchingName(String text, NewsComplexEvidence complex) {
        return names(complex).stream()
                .filter(name -> contains(text, name))
                .findFirst()
                .orElse(null);
    }

    private int longestNameLength(NewsComplexEvidence complex) {
        return names(complex).stream().mapToInt(this::normalizedLength).max().orElse(0);
    }

    private List<String> names(NewsComplexEvidence complex) {
        List<String> names = new ArrayList<>();
        if (hasText(complex.canonicalName())) names.add(complex.canonicalName());
        if (hasText(complex.tradeName())) names.add(complex.tradeName());
        names.addAll(complex.approvedAliases().stream().filter(this::hasText).toList());
        return names.stream()
                .distinct()
                .sorted(Comparator.comparingInt(this::normalizedLength).reversed())
                .toList();
    }

    private boolean contains(String text, String token) {
        if (!hasText(token)) {
            return false;
        }
        String normalizedToken = normalize(token);
        return (" " + text + " ").contains(" " + normalizedToken + " ");
    }

    private int normalizedLength(String value) {
        return normalize(value).replace(" ", "").length();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                        .replaceAll("[^0-9a-z가-힣 ]", " ")
                        .replaceAll("\\s+", " ")
                        .trim();
    }

    private List<String> compactTokens(String... values) {
        return java.util.Arrays.stream(values).filter(this::hasText).distinct().toList();
    }
}
