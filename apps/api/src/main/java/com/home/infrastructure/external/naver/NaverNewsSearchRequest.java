package com.home.infrastructure.external.naver;

public record NaverNewsSearchRequest(String query, int display, int start, String sort) {

	public NaverNewsSearchRequest {
		if (query == null || query.isBlank()) {
			throw new IllegalArgumentException("query must not be blank");
		}
		if (display < 1 || display > 100) {
			throw new IllegalArgumentException("display must be between 1 and 100");
		}
		if (start < 1 || start > 1000) {
			throw new IllegalArgumentException("start must be between 1 and 1000");
		}
		sort = normalizeSort(sort);
		query = query.trim();
	}

	private static String normalizeSort(String sort) {
		if (sort == null || sort.isBlank()) {
			return "date";
		}
		String normalized = sort.trim().toLowerCase(java.util.Locale.ROOT);
		if (!normalized.equals("date") && !normalized.equals("sim")) {
			throw new IllegalArgumentException("sort must be date or sim");
		}
		return normalized;
	}
}
