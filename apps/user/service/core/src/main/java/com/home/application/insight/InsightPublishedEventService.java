package com.home.application.insight;

import com.home.application.insight.port.InsightEventRepository;
import com.home.domain.user.insight.InsightInboxItem;
import com.home.domain.user.insight.InsightSubscription;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InsightPublishedEventService {

    private static final Duration INBOX_RETENTION = Duration.ofDays(90);

    private final InsightEventRepository repository;
    private final Clock clock;

    public InsightPublishedEventService(InsightEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public int consume(PublishedInsightEvent event) {
        Instant processedAt = clock.instant();
        List<InsightInboxItem> items = isDeliverable(event)
                ? repository.findCandidates(event.scopeType(), event.regionCode()).stream()
                        .filter(subscription -> isEligible(subscription, event))
                        .map(subscription -> toInboxItem(subscription, event, processedAt))
                        .toList()
                : List.of();
        return repository.apply(event, items, processedAt) ? items.size() : 0;
    }

    private static boolean isDeliverable(PublishedInsightEvent event) {
        return "NewsSnapshotPublished".equals(event.eventType())
                || "WEEKLY".equals(event.insightKind())
                || "ROLLING_7D".equals(event.insightKind());
    }

    private static boolean isEligible(InsightSubscription subscription, PublishedInsightEvent event) {
        if (!subscription.inAppEnabled()) return false;
        return switch (event.eventType()) {
            case "NewsSnapshotPublished" -> subscription.dailyNewsEnabled();
            case "InsightPublished" -> subscription.weeklyTradeEnabled();
            default -> false;
        };
    }

    private static InsightInboxItem toInboxItem(
            InsightSubscription subscription, PublishedInsightEvent event, Instant createdAt) {
        UUID inboxId = UUID.nameUUIDFromBytes(
                (event.eventId() + ":" + subscription.userId()).getBytes(StandardCharsets.UTF_8));
        return new InsightInboxItem(
                inboxId,
                subscription.userId(),
                event.eventId(),
                title(event),
                event.snapshotId().toString(),
                deepLink(event),
                createdAt,
                createdAt.plus(INBOX_RETENTION));
    }

    private static String title(PublishedInsightEvent event) {
        if ("NewsSnapshotPublished".equals(event.eventType())) {
            return "NATIONWIDE".equals(event.scopeType()) ? "전국 부동산 뉴스" : "관심 지역 부동산 뉴스";
        }
        return "주간 거래 인사이트";
    }

    private static String deepLink(PublishedInsightEvent event) {
        if ("NATIONWIDE".equals(event.scopeType())) return "/insights?scope=NATIONWIDE";
        return "/insights?scope=SIDO&regionCode=" + event.regionCode();
    }
}
