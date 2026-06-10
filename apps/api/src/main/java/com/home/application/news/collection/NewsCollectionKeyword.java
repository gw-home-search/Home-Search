package com.home.application.news.collection;

import java.util.Objects;

import com.home.domain.news.NewsCollectionKeywordCadence;
import com.home.domain.news.NewsCollectionKeywordType;

public record NewsCollectionKeyword(
	long id,
	String queryText,
	NewsCollectionKeywordType keywordType,
	String sourceTable,
	String sourceId,
	int priority,
	NewsCollectionKeywordCadence cadence
) {

	public NewsCollectionKeyword {
		if (id < 1) {
			throw new IllegalArgumentException("id must be positive");
		}
		queryText = requireText(queryText, "queryText");
		keywordType = Objects.requireNonNull(keywordType, "keywordType must not be null");
		cadence = Objects.requireNonNull(cadence, "cadence must not be null");
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value.trim();
	}
}
