package com.home.user.worker;

import com.home.application.insight.InsightPublishedEventService;
import java.nio.charset.StandardCharsets;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InsightEventListener {

    private static final int MAX_MESSAGE_BYTES = 262_144;

    private final InsightEventMessageParser parser;
    private final InsightPublishedEventService service;

    public InsightEventListener(InsightEventMessageParser parser, InsightPublishedEventService service) {
        this.parser = parser;
        this.service = service;
    }

    @KafkaListener(
            topics = "${home.insight.topic:property.insight-events.v1}",
            groupId = "${spring.kafka.consumer.group-id:user-digest-v1}")
    public void onMessage(String message) {
        if (message == null || message.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            throw new InvalidInsightEventMessageException("event message exceeds the 256KiB contract");
        }
        service.consume(parser.parse(message));
    }
}
