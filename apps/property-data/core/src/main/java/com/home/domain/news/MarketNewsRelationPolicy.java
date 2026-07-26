package com.home.domain.news;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MarketNewsRelationPolicy {

    private static final List<String> ADMINISTRATIVE_SUFFIXES = List.of(
            "특별자치도", "특별자치시", "특별시", "광역시", "-myeon", "-dong", "-gun", "-eup", "-do", "-si", "-gu", "-ri", "도", "시",
            "군", "구", "동", "읍", "면", "리");

    public List<MarketNewsRelationMatch> match(String title, String description, List<NewsComplexEvidence> complexes) {
        return match(title, description, index(complexes));
    }

    public IndexedCorpus index(List<NewsComplexEvidence> complexes) {
        List<NewsComplexEvidence> ordered = complexes == null
                ? List.of()
                : complexes.stream()
                        .sorted(Comparator.comparingInt(this::longestNameLength)
                                .reversed()
                                .thenComparingLong(NewsComplexEvidence::complexId))
                        .toList();
        return new IndexedCorpus(ordered.stream().map(this::indexComplex).toList());
    }

    public List<MarketNewsRelationMatch> match(String title, String description, IndexedCorpus corpus) {
        String text = normalize(title + " " + description);
        List<MarketNewsRelationMatch> matches = new ArrayList<>();
        Set<String> regionRelations = new LinkedHashSet<>();
        for (IndexedComplex indexed : corpus.complexes) {
            NewsComplexEvidence complex = indexed.evidence();
            NewsRegionEvidence region = complex.region();
            if (region == null) {
                continue;
            }
            String matchedName = matchingName(text, indexed);
            boolean sigungu = containsNormalized(text, indexed.sigunguName());
            boolean dong = containsNormalized(text, indexed.dongName());
            boolean sido = containsNormalized(text, indexed.sidoName());
            if (matchedName != null
                    && !indexed.geographicNames().contains(normalize(matchedName))
                    && sigungu
                    && (!requiresDong(complex, matchedName) || dong)) {
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

    private IndexedComplex indexComplex(NewsComplexEvidence complex) {
        NewsRegionEvidence region = complex.region();
        return new IndexedComplex(
                complex,
                names(complex).stream()
                        .map(name -> new IndexedName(name, normalize(name)))
                        .toList(),
                normalize(region == null ? null : region.sidoName()),
                normalize(region == null ? null : region.sigunguName()),
                normalize(region == null ? null : region.dongName()),
                geographicNames(region));
    }

    private Set<String> geographicNames(NewsRegionEvidence region) {
        if (region == null) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        addGeographicName(names, region.sidoName());
        addGeographicName(names, region.sigunguName());
        addGeographicName(names, region.dongName());
        return Set.copyOf(names);
    }

    private void addGeographicName(Set<String> names, String value) {
        if (!hasText(value)) {
            return;
        }
        names.add(normalize(value));
        String stripped = stripAdministrativeSuffix(value);
        if (hasText(stripped)) {
            names.add(normalize(stripped));
        }
    }

    private String stripAdministrativeSuffix(String value) {
        String trimmed = value.trim();
        for (String suffix : ADMINISTRATIVE_SUFFIXES) {
            if (trimmed.endsWith(suffix) && trimmed.length() > suffix.length()) {
                return trimmed.substring(0, trimmed.length() - suffix.length());
            }
        }
        return trimmed;
    }

    private boolean requiresDong(NewsComplexEvidence complex, String matchedName) {
        return complex.nationwideDuplicateName() || normalizedLength(matchedName) <= 4;
    }

    private String matchingName(String text, IndexedComplex complex) {
        return complex.names().stream()
                .filter(name -> containsNormalized(text, name.normalized()))
                .map(IndexedName::original)
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
        return containsNormalized(text, normalize(token));
    }

    private boolean containsNormalized(String text, String normalizedToken) {
        return !normalizedToken.isBlank() && (" " + text + " ").contains(" " + normalizedToken + " ");
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

    public static final class IndexedCorpus {

        private final List<IndexedComplex> complexes;

        private IndexedCorpus(List<IndexedComplex> complexes) {
            this.complexes = List.copyOf(complexes);
        }
    }

    private record IndexedComplex(
            NewsComplexEvidence evidence,
            List<IndexedName> names,
            String sidoName,
            String sigunguName,
            String dongName,
            Set<String> geographicNames) {}

    private record IndexedName(String original, String normalized) {}
}
