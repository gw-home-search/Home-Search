package com.home.application.news.collection;

import java.util.Objects;

import com.home.domain.news.NewsCollectionArticleDisposition;

public record NewsCollectionArticleDiscovery(
	String source,
	String sourceKey,
	int providerRank,
	NewsCollectionArticleDisposition disposition
) {

	public NewsCollectionArticleDiscovery {
		source = requireText(source, "source");
		sourceKey = requireText(sourceKey, "sourceKey");
		if (providerRank < 1) {
			throw new IllegalArgumentException("providerRank must be positive");
		}
		disposition = Objects.requireNonNull(disposition, "disposition must not be null");
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value.trim();
	}
}
