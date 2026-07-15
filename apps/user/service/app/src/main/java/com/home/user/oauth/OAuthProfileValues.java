package com.home.user.oauth;

import java.util.Map;

final class OAuthProfileValues {
    private OAuthProfileValues() {}

    static String text(Object value) {
        if (value == null) return null;
        String normalized = value.toString().trim();
        return normalized.isEmpty() || "null".equalsIgnoreCase(normalized) ? null : normalized;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    static String requiredSubject(Object value) {
        String subject = text(value);
        if (subject == null || subject.length() > 255)
            throw new IllegalArgumentException("provider subject is required");
        return subject;
    }
}
