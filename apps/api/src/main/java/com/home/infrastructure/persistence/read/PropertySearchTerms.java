package com.home.infrastructure.persistence.read;

import java.util.Locale;

record PropertySearchTerms(
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
		String normalizedQuery = normalized.isBlank() ? null : normalized;
		return new PropertySearchTerms(
			lowerQuery,
			"%" + lowerQuery + "%",
			lowerQuery + "%",
			normalizedQuery,
			normalizedQuery == null ? null : "%" + normalizedQuery + "%",
			normalizedQuery == null ? null : normalizedQuery + "%"
		);
	}

	private static String normalizeName(String value) {
		String text = value == null ? "" : value.trim();
		return text.replaceAll("\\s+", "")
			.replaceAll("[()\\[\\]{}.,·\\-_/]", "")
			.toLowerCase(Locale.ROOT);
	}
}
