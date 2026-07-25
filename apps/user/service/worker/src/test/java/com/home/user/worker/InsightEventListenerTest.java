package com.home.user.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.home.application.insight.InsightPublishedEventService;
import com.home.application.insight.PublishedInsightEvent;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InsightEventListenerTest {

    @Test
    @DisplayName("Kafka record는 parser 검증 뒤 application transaction으로 전달한다")
    void delegatesValidatedEvent() {
        InsightEventMessageParser parser = mock(InsightEventMessageParser.class);
        InsightPublishedEventService service = mock(InsightPublishedEventService.class);
        PublishedInsightEvent event = event();
        when(parser.parse("message")).thenReturn(event);
        var listener = new InsightEventListener(parser, service);

        listener.onMessage("message");

        verify(parser).parse("message");
        verify(service).consume(event);
    }

    @Test
    @DisplayName("256KiB를 초과한 record는 처리 전에 거부한다")
    void rejectsOversizedRecord() {
        var listener = new InsightEventListener(
                mock(InsightEventMessageParser.class), mock(InsightPublishedEventService.class));

        assertThatThrownBy(() -> listener.onMessage("가".repeat(100_000)))
                .isInstanceOf(InvalidInsightEventMessageException.class);
    }

    private static PublishedInsightEvent event() {
        return new PublishedInsightEvent(
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "InsightPublished",
                "insight-20260724",
                1,
                UUID.fromString("44444444-aaaa-4444-8444-444444444444"),
                "ROLLING_7D",
                "NATIONWIDE",
                null);
    }
}
