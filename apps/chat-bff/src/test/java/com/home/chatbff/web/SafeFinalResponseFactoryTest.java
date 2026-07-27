package com.home.chatbff.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class SafeFinalResponseFactoryTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void matchesCrossServiceFixture() throws Exception {
        Path fixture = Path.of("../../docs/fixtures/chatbot-safe-final-v1.json");
        ObjectNode expected = (ObjectNode) objectMapper.readTree(Files.readString(fixture));
        expected.put("requestId", "request-1");

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        assertThat(new SafeFinalResponseFactory(objectMapper, registry).create("request-1", "fixture"))
                .isEqualTo(expected);
        assertThat(registry.get("home.chatbot.terminal.outcome")
                        .tag("trigger", "fixture")
                        .counter()
                        .count())
                .isEqualTo(1);
    }
}
