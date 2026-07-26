package com.home.batch.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.home.application.event.PropertyEventOutboxRelayService;
import com.home.application.event.PropertyEventRelayResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

class PropertyEventRelayTaskletTest {

    private static final Instant NOW = Instant.parse("2026-07-25T03:00:00Z");

    @Test
    @DisplayName("due outbox를 batch 단위로 모두 비운 뒤 종료한다")
    void drainsDueOutboxThenFinishes() throws Exception {
        PropertyEventOutboxRelayService relay = mock(PropertyEventOutboxRelayService.class);
        when(relay.relayBatch(eq(100), any(Instant.class)))
                .thenReturn(new PropertyEventRelayResult(100, 100, 0), new PropertyEventRelayResult(2, 2, 0));

        RepeatStatus status =
                new PropertyEventRelayTasklet(relay, 100, 1000, Clock.fixed(NOW, ZoneOffset.UTC)).execute(null, null);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verify(relay, org.mockito.Mockito.times(2)).relayBatch(100, NOW);
    }

    @Test
    @DisplayName("publish 실패 row는 보존하되 batch job은 실패해 운영 경보를 발생시킨다")
    void failsJobWhenPublishWasNotAcknowledged() {
        PropertyEventOutboxRelayService relay = mock(PropertyEventOutboxRelayService.class);
        when(relay.relayBatch(eq(100), any(Instant.class))).thenReturn(new PropertyEventRelayResult(1, 0, 1));

        assertThatThrownBy(() -> new PropertyEventRelayTasklet(relay, 100, 1000, Clock.fixed(NOW, ZoneOffset.UTC))
                        .execute(null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("property event relay left 1 event(s) for retry");
    }

    @Test
    @DisplayName("max batch를 모두 사용해도 due event가 남으면 무한 실행 대신 실패한다")
    void failsWhenMaxBatchesAreExhausted() {
        PropertyEventOutboxRelayService relay = mock(PropertyEventOutboxRelayService.class);
        when(relay.relayBatch(eq(100), any(Instant.class))).thenReturn(new PropertyEventRelayResult(100, 100, 0));

        assertThatThrownBy(() -> new PropertyEventRelayTasklet(relay, 100, 1, Clock.fixed(NOW, ZoneOffset.UTC))
                        .execute(null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("property event relay reached maxBatches before draining due events");
    }

    @Test
    @DisplayName("tasklet은 application relay limit과 실행 상한을 방어한다")
    void rejectsUnsafeLimits() {
        PropertyEventOutboxRelayService relay = mock(PropertyEventOutboxRelayService.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertThatThrownBy(() -> new PropertyEventRelayTasklet(relay, 0, 1, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PropertyEventRelayTasklet(relay, 101, 1, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PropertyEventRelayTasklet(relay, 100, 0, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
