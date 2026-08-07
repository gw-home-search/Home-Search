package com.home.chatbff.web;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class ChatbotTerminalRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger("CHATBOT_TERMINAL");

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    ChatbotTerminalRecorder(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    void record(
            String requestId,
            ChatbotTerminalOutcome outcome,
            boolean safeFinal,
            long latencyMs,
            int upstreamStatus,
            JsonNode response) {
        LOGGER.info(terminalPayload(requestId, outcome, safeFinal, latencyMs, upstreamStatus, response)
                .toString());
        meterRegistry.counter("ChatbotRequestCount").increment();
        if (outcome == ChatbotTerminalOutcome.SUCCESS) {
            meterRegistry.counter("ChatbotAnsweredCount").increment();
        }
        if (outcome == ChatbotTerminalOutcome.PARTIAL) {
            meterRegistry.counter("ChatbotPartialCount").increment();
        }
        if (safeFinal) {
            meterRegistry.counter("ChatbotSafeFinalCount").increment();
        }
        if (outcome == ChatbotTerminalOutcome.UPSTREAM_TIMEOUT) {
            meterRegistry.counter("ChatbotUpstreamTimeoutCount").increment();
        }
        if (outcome == ChatbotTerminalOutcome.CONTRACT_REJECTED) {
            meterRegistry.counter("ChatbotContractRejectedCount").increment();
        }
        meterRegistry.timer("ChatbotLatencyMs").record(Duration.ofMillis(Math.max(latencyMs, 0)));
    }

    ObjectNode terminalPayload(
            String requestId,
            ChatbotTerminalOutcome outcome,
            boolean safeFinal,
            long latencyMs,
            int upstreamStatus,
            JsonNode response) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("event", "chatbot_terminal");
        payload.put("requestId", requestId);
        payload.put("intent", inferredIntent(response));
        ArrayNode capabilities = payload.putArray("capabilities");
        inferredCapabilities(response).forEach(capabilities::add);
        payload.put("outcome", outcome.name());
        payload.put("safeFinal", safeFinal);
        payload.put("latencyMs", Math.max(latencyMs, 0));
        payload.put("upstreamStatus", upstreamStatus);
        return payload;
    }

    private Set<String> inferredCapabilities(JsonNode response) {
        Set<String> capabilities = new LinkedHashSet<>();
        if (response == null) return capabilities;
        JsonNode evidence = response.get("evidenceSummary");
        JsonNode values = evidence == null ? null : evidence.get("capabilities");
        if (values != null && values.isArray()) {
            values.forEach(value -> {
                if (value.isTextual()) capabilities.add(value.asText());
            });
        }
        return capabilities;
    }

    private String inferredIntent(JsonNode response) {
        Set<String> capabilities = inferredCapabilities(response);
        if (capabilities.contains("recommendation")) return "RECOMMENDATION";
        if (capabilities.contains("comparison")) return "COMPARISON";
        if (capabilities.contains("price_trend")) return "TREND";
        if (capabilities.contains("academy_lookup") && capabilities.contains("rail_station_lookup")) {
            return "REFERENCE_COMPOUND";
        }
        if (capabilities.contains("complex_identity") && capabilities.contains("recent_trade_lookup")) {
            return "COMPLEX_OVERVIEW";
        }
        if (capabilities.contains("complex_identity") || capabilities.contains("recent_trade_lookup")) {
            return "DIRECT_PROPERTY";
        }
        return "UNKNOWN";
    }
}
