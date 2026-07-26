package com.home.application.insight;

import java.util.Set;
import java.util.UUID;

public record PublishedInsightEvent(
        UUID eventId,
        String eventType,
        String aggregateId,
        long aggregateVersion,
        UUID snapshotId,
        String insightKind,
        String scopeType,
        String regionCode) {

    private static final Set<String> EVENT_TYPES = Set.of("InsightPublished", "NewsSnapshotPublished");
    private static final Set<String> INSIGHT_KINDS = Set.of("DAILY", "WEEKLY", "ROLLING_7D");
    private static final Set<String> SCOPE_TYPES = Set.of("NATIONWIDE", "SIDO");

    public PublishedInsightEvent {
        if (eventId == null) throw new IllegalArgumentException("eventId is required");
        if (!EVENT_TYPES.contains(eventType)) throw new IllegalArgumentException("eventType is unsupported");
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("aggregateId is required");
        }
        if (aggregateVersion < 1) throw new IllegalArgumentException("aggregateVersion must be positive");
        if (snapshotId == null) throw new IllegalArgumentException("snapshotId is required");
        if (!SCOPE_TYPES.contains(scopeType)) throw new IllegalArgumentException("scopeType is unsupported");
        if ("NATIONWIDE".equals(scopeType) && regionCode != null) {
            throw new IllegalArgumentException("NATIONWIDE event must not have regionCode");
        }
        if ("SIDO".equals(scopeType) && (regionCode == null || !regionCode.matches("[0-9]{2}"))) {
            throw new IllegalArgumentException("SIDO event requires a two-digit regionCode");
        }
        if ("InsightPublished".equals(eventType) && !INSIGHT_KINDS.contains(insightKind)) {
            throw new IllegalArgumentException("InsightPublished requires a supported insightKind");
        }
        if ("NewsSnapshotPublished".equals(eventType) && insightKind != null) {
            throw new IllegalArgumentException("NewsSnapshotPublished must not have insightKind");
        }
    }
}
