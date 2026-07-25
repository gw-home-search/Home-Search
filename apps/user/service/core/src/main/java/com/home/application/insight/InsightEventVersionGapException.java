package com.home.application.insight;

public final class InsightEventVersionGapException extends RuntimeException {
    public InsightEventVersionGapException(
            String eventType, String aggregateId, long currentVersion, long eventVersion) {
        super("Insight event version gap: eventType=%s, aggregateId=%s, currentVersion=%d, eventVersion=%d"
                .formatted(eventType, aggregateId, currentVersion, eventVersion));
    }
}
