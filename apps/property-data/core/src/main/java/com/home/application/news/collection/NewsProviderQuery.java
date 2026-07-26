package com.home.application.news.collection;

public record NewsProviderQuery(String query, int start, int display) {

    public NewsProviderQuery {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        if (start < 1 || start > 1000) {
            throw new IllegalArgumentException("start must be between 1 and 1000");
        }
        if (display != 100) {
            throw new IllegalArgumentException("display must be 100");
        }
    }
}
