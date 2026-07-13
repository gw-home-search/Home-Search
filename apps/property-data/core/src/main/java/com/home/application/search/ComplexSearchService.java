package com.home.application.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.home.application.read.ComplexSuggestionResult;
import com.home.application.read.InvalidReadRequestException;
import com.home.application.read.SearchComplexResult;

import org.springframework.stereotype.Service;

@Service
public class ComplexSearchService {

	private static final int SUGGESTION_LIMIT = 8;
	private static final int MAX_QUERY_CODE_POINTS = 100;
	private static final int MAX_QUERY_TOKENS = 8;

	private final ComplexSearchReader reader;

	public ComplexSearchService(ComplexSearchReader reader) {
		this.reader = Objects.requireNonNull(reader);
	}

	public List<SearchComplexResult> searchComplexes(String query) {
		String normalized = normalizeQuery(query);
		return normalized.isEmpty() ? List.of() : reader.searchComplexes(normalized);
	}

	public List<ComplexSuggestionResult> suggestComplexes(String query) {
		String normalized = normalizeQuery(query);
		return normalized.isEmpty() ? List.of() : reader.suggestComplexes(normalized, SUGGESTION_LIMIT);
	}

	private String normalizeQuery(String query) {
		String trimmed = query == null ? "" : query.strip();
		if (trimmed.codePointCount(0, trimmed.length()) > MAX_QUERY_CODE_POINTS) {
			throw new InvalidReadRequestException("search query must not exceed 100 characters");
		}
		if (trimmed.isEmpty()) {
			return "";
		}
		LinkedHashMap<String, String> uniqueTokens = new LinkedHashMap<>();
		for (String token : trimmed.split("(?U)\\s+")) {
			uniqueTokens.putIfAbsent(token.toLowerCase(Locale.ROOT), token);
		}
		if (uniqueTokens.size() > MAX_QUERY_TOKENS) {
			throw new InvalidReadRequestException("search query must not exceed 8 unique tokens");
		}
		return String.join(" ", uniqueTokens.values());
	}
}
