package com.home.application.insight;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.insight.port.InsightEventRepository;
import com.home.domain.user.insight.InsightInboxItem;
import com.home.domain.user.insight.InsightSubscription;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InsightPublishedEventServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-25T03:00:00Z");

    @Test
    @DisplayName("rolling insight는 in-app과 weekly trade를 모두 동의한 지역 subscriber만 inbox로 만든다")
    void createsOnlyEligibleRegionalTradeInboxItems() {
        RecordingEventRepository repository = new RecordingEventRepository(List.of(
                new InsightSubscription(41, true, false, true, true, List.of("11")),
                new InsightSubscription(42, true, false, true, false, List.of("11")),
                new InsightSubscription(43, false, false, true, true, List.of("11"))));
        InsightPublishedEventService service = new InsightPublishedEventService(repository, fixedClock());
        PublishedInsightEvent event = new PublishedInsightEvent(
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "InsightPublished",
                "snapshot-aggregate-11",
                3,
                UUID.fromString("44444444-aaaa-4444-8444-444444444444"),
                "ROLLING_7D",
                "SIDO",
                "11");

        int created = service.consume(event);

        assertThat(created).isEqualTo(1);
        assertThat(repository.items).singleElement().satisfies(item -> {
            assertThat(item.userId()).isEqualTo(41);
            assertThat(item.digestId()).isEqualTo(event.eventId());
            assertThat(item.propertySnapshotId()).isEqualTo(event.snapshotId().toString());
            assertThat(item.deepLink()).isEqualTo("/insights?scope=SIDO&regionCode=11");
            assertThat(item.createdAt()).isEqualTo(NOW);
            assertThat(item.expiresAt()).isEqualTo(NOW.plusSeconds(90L * 86_400));
        });
    }

    @Test
    @DisplayName("daily insight는 weekly subscription inbox를 만들지 않고 duplicate 결과도 그대로 no-op 처리한다")
    void ignoresDailyTradeAndDuplicateEvents() {
        RecordingEventRepository repository =
                new RecordingEventRepository(List.of(new InsightSubscription(41, true, false, true, true, List.of())));
        repository.applied = false;
        InsightPublishedEventService service = new InsightPublishedEventService(repository, fixedClock());

        int created = service.consume(new PublishedInsightEvent(
                UUID.fromString("44444444-4444-4444-8444-444444444445"),
                "InsightPublished",
                "snapshot-aggregate",
                1,
                UUID.fromString("44444444-aaaa-4444-8444-444444444445"),
                "DAILY",
                "NATIONWIDE",
                null));

        assertThat(created).isZero();
        assertThat(repository.items).isEmpty();
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static final class RecordingEventRepository implements InsightEventRepository {
        private final List<InsightSubscription> candidates;
        private List<InsightInboxItem> items = List.of();
        private boolean applied = true;

        private RecordingEventRepository(List<InsightSubscription> candidates) {
            this.candidates = candidates;
        }

        @Override
        public List<InsightSubscription> findCandidates(String scopeType, String regionCode) {
            return candidates;
        }

        @Override
        public boolean apply(PublishedInsightEvent event, List<InsightInboxItem> items, Instant processedAt) {
            this.items = List.copyOf(items);
            return applied;
        }
    }
}
