package com.home.infrastructure.persistence.search;

import java.util.Locale;

record PropertySearchTerms(String query, String lowerQuery, String normalizedQuery) {

    static PropertySearchTerms from(String query) {
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        String normalized = normalizeName(query);
        String normalizedQuery = normalized.isBlank() ? "" : normalized;
        return new PropertySearchTerms(query, lowerQuery, normalizedQuery);
    }

    boolean isSingleTwoCodePointQuery() {
        return query.codePointCount(0, query.length()) == 2;
    }

    boolean isSingleTermQuery() {
        return query.trim().codePoints().noneMatch(Character::isWhitespace);
    }

    String rawPrefixPattern() {
        return prefixPattern(lowerQuery);
    }

    String normalizedPrefixPattern() {
        return prefixPattern(normalizedQuery);
    }

    String rawContainsPattern() {
        return containsPattern(lowerQuery);
    }

    String normalizedContainsPattern() {
        return containsPattern(normalizedQuery);
    }

    private static String containsPattern(String value) {
        return "%" + escapeLikePattern(value) + "%";
    }

    private static String prefixPattern(String value) {
        return escapeLikePattern(value) + "%";
    }

    private static String escapeLikePattern(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String normalizeName(String value) {
        String text = value == null ? "" : value.trim();
        return text.replaceAll("[\\s\\p{P}]+", "").toLowerCase(Locale.ROOT);
    }
}
