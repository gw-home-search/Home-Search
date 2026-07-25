package com.home.domain.user.insight;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record InsightInboxItem(
        UUID inboxId,
        long userId,
        UUID digestId,
        String title,
        String propertySnapshotId,
        String deepLink,
        Instant createdAt,
        Instant expiresAt) {

    public InsightInboxItem {
        Objects.requireNonNull(inboxId);
        Objects.requireNonNull(digestId);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(expiresAt);
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        title = requireText(title, "title");
        propertySnapshotId = requireText(propertySnapshotId, "propertySnapshotId");
        deepLink = requireText(deepLink, "deepLink");
        if (!deepLink.matches("/insights(?:[?][A-Za-z0-9%&=_-]+)?")) {
            throw new IllegalArgumentException("deepLink must stay under /insights");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
