package com.home.domain.user;

public record UserProfile(String displayName, String email, String profileImage) {
    public static final String DEFAULT_DISPLAY_NAME = "홈서치 사용자";

    public UserProfile {
        displayName = normalize(displayName);
        email = normalize(email);
        profileImage = normalize(profileImage);
        if (displayName != null && displayName.length() > 100) {
            throw new IllegalArgumentException("displayName must be at most 100 characters");
        }
        if (email != null && email.length() > 320) {
            throw new IllegalArgumentException("email must be at most 320 characters");
        }
    }

    public UserProfile forNewUser() {
        return displayName == null ? new UserProfile(DEFAULT_DISPLAY_NAME, email, profileImage) : this;
    }

    public UserProfile merge(UserProfile update) {
        return new UserProfile(
                update.displayName != null ? update.displayName : displayName,
                update.email != null ? update.email : email,
                update.profileImage != null ? update.profileImage : profileImage);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() || "null".equalsIgnoreCase(normalized) ? null : normalized;
    }
}
