package com.home.chatbff.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ChatbotTerminalRecorderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void terminalPayloadContainsOnlyOperationalMetadataAndRecordsMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatbotTerminalRecorder recorder = new ChatbotTerminalRecorder(objectMapper, registry);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", "partial_success");
        response.put("answer", "노출되면 안 되는 답변");
        response.put("question", "노출되면 안 되는 질문");
        response.set("fragments", objectMapper.valueToTree(List.of()));

        ObjectNode payload =
                recorder.terminalPayload("request-1", ChatbotTerminalOutcome.PARTIAL, true, 1840, 200, response);
        recorder.record("request-1", ChatbotTerminalOutcome.PARTIAL, true, 1840, 200, response);

        assertThat(payload.get("event").asText()).isEqualTo("chatbot_terminal");
        assertThat(payload.get("requestId").asText()).isEqualTo("request-1");
        assertThat(payload.get("outcome").asText()).isEqualTo("PARTIAL");
        assertThat(payload.get("safeFinal").asBoolean()).isTrue();
        assertThat(payload.toString()).doesNotContain("노출되면 안 되는");
        assertThat(registry.get("ChatbotRequestCount").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ChatbotPartialCount").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ChatbotSafeFinalCount").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ChatbotLatencyMs").timer().count()).isEqualTo(1L);
    }

    @Test
    void classifiesOnlyBoundedIntentLabelsFromCapabilities() {
        ChatbotTerminalRecorder recorder = new ChatbotTerminalRecorder(objectMapper, new SimpleMeterRegistry());
        Map<List<String>, String> examples = Map.of(
                List.of("complex_identity"), "DIRECT_PROPERTY",
                List.of("recent_trade_lookup"), "DIRECT_PROPERTY",
                List.of("complex_identity", "recent_trade_lookup"), "COMPLEX_OVERVIEW",
                List.of("academy_lookup", "rail_station_lookup"), "REFERENCE_COMPOUND",
                List.of("price_trend"), "TREND",
                List.of("comparison"), "COMPARISON",
                List.of("recommendation"), "RECOMMENDATION");

        examples.forEach((capabilities, expected) -> {
            ObjectNode response = objectMapper.createObjectNode();
            response.withObject("evidenceSummary").set("capabilities", objectMapper.valueToTree(capabilities));

            assertThat(recorder.terminalPayload("request-1", ChatbotTerminalOutcome.SUCCESS, false, 1, 200, response)
                            .get("intent")
                            .asText())
                    .isEqualTo(expected);
        });
    }
}
