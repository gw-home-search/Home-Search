package com.home.application.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PropertyEventOutboxRepository {

    List<PendingPropertyEvent> lockPending(int limit, Instant now);

    void markPublished(UUID eventId, Instant publishedAt);

    void markFailed(UUID eventId, Instant nextAttemptAt, String failureType);

    int deletePublishedBefore(Instant cutoff, int limit);
}
