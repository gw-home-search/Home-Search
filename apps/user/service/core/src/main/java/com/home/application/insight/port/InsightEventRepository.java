package com.home.application.insight.port;

import com.home.application.insight.PublishedInsightEvent;
import com.home.domain.user.insight.InsightInboxItem;
import java.time.Instant;
import java.util.List;

public interface InsightEventRepository {
    List<com.home.domain.user.insight.InsightSubscription> findCandidates(String scopeType, String regionCode);

    boolean apply(PublishedInsightEvent event, List<InsightInboxItem> items, Instant processedAt);
}
