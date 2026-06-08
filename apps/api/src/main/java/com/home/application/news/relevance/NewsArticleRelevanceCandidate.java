package com.home.application.news.relevance;

public record NewsArticleRelevanceCandidate(
	long articleObservationId,
	String source,
	String sourceKey,
	String publisher,
	String title,
	String snippet
) {

	public NewsArticleRelevanceCandidate {
		source = requireText(source, "source");
		sourceKey = requireText(sourceKey, "sourceKey");
		publisher = requireText(publisher, "publisher");
		title = title == null ? "" : title.trim();
		snippet = snippet == null ? "" : snippet.trim();
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value.trim();
	}
}
