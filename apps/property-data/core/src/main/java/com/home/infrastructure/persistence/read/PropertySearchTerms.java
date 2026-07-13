package com.home.infrastructure.persistence.read;

import java.util.Locale;

record PropertySearchTerms(
	String query,
	String lowerQuery,
	String pattern,
	String prefixPattern,
	String normalizedQuery,
	String normalizedPattern,
	String normalizedPrefixPattern
) {

	static PropertySearchTerms from(String query) {
		String lowerQuery = query.toLowerCase(Locale.ROOT);
		String normalized = normalizeName(query);
		String normalizedQuery = normalized.isBlank() ? "" : normalized;
		return new PropertySearchTerms(
			query,
			lowerQuery,
			"%" + escapeLikePattern(lowerQuery) + "%",
			escapeLikePattern(lowerQuery) + "%",
			normalizedQuery,
			"%" + escapeLikePattern(normalizedQuery) + "%",
			escapeLikePattern(normalizedQuery) + "%"
		);
	}

	private static String escapeLikePattern(String value) {
		return value.replace("\\", "\\\\")
			.replace("%", "\\%")
			.replace("_", "\\_");
	}

	private static String normalizeName(String value) {
		String text = value == null ? "" : value.trim();
		return text.replaceAll("[\\s\\p{P}]+", "")
			.toLowerCase(Locale.ROOT);
	}
}
