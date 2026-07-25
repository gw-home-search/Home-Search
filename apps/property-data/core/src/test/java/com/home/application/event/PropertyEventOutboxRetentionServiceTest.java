package com.home.application.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PropertyEventOutboxRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-25T03:00:00Z");

    @Test
    @DisplayName("30일 cutoff로 bounded batch를 반복하고 마지막 partial batch에서 중단한다")
    void deletesExpiredPublishedEventsInBoundedBatches() {
        RecordingRepository repository = new RecordingRepository(100, 100, 7);
        PropertyEventOutboxRetentionService service = new PropertyEventOutboxRetentionService(repository);

        int deleted = service.deleteExpired(NOW, Duration.ofDays(30), 100, 10);

        assertThat(deleted).isEqualTo(207);
        assertThat(repository.cutoffs)
                .containsExactly(
                        NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(30)));
        assertThat(repository.limits).containsExactly(100, 100, 100);
    }

    @Test
    @DisplayName("retention과 batch 경계를 벗어난 실행은 DB를 호출하기 전에 거부한다")
    void rejectsUnsafeRetentionArguments() {
        RecordingRepository repository = new RecordingRepository();
        PropertyEventOutboxRetentionService service = new PropertyEventOutboxRetentionService(repository);

        assertThatThrownBy(() -> service.deleteExpired(NOW, Duration.ZERO, 100, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.deleteExpired(NOW, Duration.ofDays(30), 1001, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.deleteExpired(NOW, Duration.ofDays(30), 100, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.cutoffs).isEmpty();
    }

    @Test
    @DisplayName("max batch를 모두 소진해도 만료 row가 남을 수 있으면 성공으로 오인하지 않는다")
    void failsWhenMaxBatchesMayNotHaveDrainedExpiredRows() {
        RecordingRepository repository = new RecordingRepository(100);
        PropertyEventOutboxRetentionService service = new PropertyEventOutboxRetentionService(repository);

        assertThatThrownBy(() -> service.deleteExpired(NOW, Duration.ofDays(30), 100, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxBatches");
    }

    private static final class RecordingRepository implements PropertyEventOutboxRepository {

        private final Queue<Integer> deleteResults = new ArrayDeque<>();
        private final java.util.ArrayList<Instant> cutoffs = new java.util.ArrayList<>();
        private final java.util.ArrayList<Integer> limits = new java.util.ArrayList<>();

        private RecordingRepository(Integer... deleteResults) {
            this.deleteResults.addAll(List.of(deleteResults));
        }

        @Override
        public List<PendingPropertyEvent> lockPending(int limit, Instant now) {
            throw new UnsupportedOperationException("not used by retention tests");
        }

        @Override
        public void markPublished(UUID eventId, Instant publishedAt) {
            throw new UnsupportedOperationException("not used by retention tests");
        }

        @Override
        public void markFailed(UUID eventId, Instant nextAttemptAt, String failureType) {
            throw new UnsupportedOperationException("not used by retention tests");
        }

        @Override
        public int deletePublishedBefore(Instant cutoff, int limit) {
            cutoffs.add(cutoff);
            limits.add(limit);
            return deleteResults.remove();
        }
    }
}
