package com.home.domain.user;

public record OAuthIdentityKey(OAuthProvider provider, String providerSubject) {
    public OAuthIdentityKey {
        if (provider == null) throw new IllegalArgumentException("provider is required");
        providerSubject = normalize(providerSubject);
        if (providerSubject == null || providerSubject.length() > 255) {
            throw new IllegalArgumentException("providerSubject is required and must be at most 255 characters");
        }
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() || "null".equalsIgnoreCase(normalized) ? null : normalized;
    }
}
