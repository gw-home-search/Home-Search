package com.home.chatbff.web;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class SafeFinalResponseFactory {
    static final String ANSWER = "일시적인 문제로 답변을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.";

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    SafeFinalResponseFactory(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    JsonNode create(String requestId, String trigger) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("success", false);
        response.put("status", "failed");
        response.set("fragments", array());
        response.set("result", objectMapper.createObjectNode());
        response.put("message", "");
        ObjectNode execution = objectMapper.createObjectNode();
        execution.put("total", 0);
        execution.put("succeeded", 0);
        execution.put("failed", 0);
        response.set("executionSummary", execution);
        response.put("answer", ANSWER);
        response.put("resolvedQuestion", "");
        ObjectNode resolution = objectMapper.createObjectNode();
        resolution.put("version", 1);
        resolution.put("answerMode", "NO_RESULT");
        resolution.set("goals", array());
        resolution.set("assumptions", array());
        resolution.set("omissions", array());
        response.set("conversationResolution", resolution);
        response.putNull("conversationMemoryPatch");
        response.set("uiActions", array());
        response.set("uiArtifacts", array());
        response.putNull("uiSummary");
        response.putNull("uiReport");
        response.put("requestId", requestId);
        response.set("citations", array());
        response.putNull("dataAsOf");
        response.set("limitations", array());
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("status", "unavailable");
        evidence.set("capabilities", array());
        evidence.put("factCount", 0);
        evidence.put("citationCount", 0);
        response.set("evidenceSummary", evidence);
        ObjectNode outcome = objectMapper.createObjectNode();
        outcome.put("version", 1);
        outcome.put("status", "UNAVAILABLE");
        outcome.put("reason", "TEMPORARY_FAILURE");
        outcome.put("retryable", true);
        response.set("terminalOutcome", outcome);
        meterRegistry
                .counter(
                        "home.chatbot.terminal.outcome",
                        "layer",
                        "bff",
                        "status",
                        "UNAVAILABLE",
                        "reason",
                        "TEMPORARY_FAILURE",
                        "trigger",
                        trigger)
                .increment();
        return response;
    }

    private ArrayNode array() {
        return objectMapper.createArrayNode();
    }
}
