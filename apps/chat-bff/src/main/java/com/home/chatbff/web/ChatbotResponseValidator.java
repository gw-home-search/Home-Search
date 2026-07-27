package com.home.chatbff.web;

import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
final class ChatbotResponseValidator {
    private static final Map<String, Set<String>> REASONS = Map.of(
            "ANSWERED", Set.of("COMPLETED"),
            "PARTIAL", Set.of("PARTIAL_EVIDENCE"),
            "CLARIFICATION", Set.of("AMBIGUOUS_ENTITY"),
            "UNAVAILABLE", Set.of("INSUFFICIENT_EVIDENCE", "OUT_OF_SCOPE", "TEMPORARY_FAILURE"));

    boolean isValid(JsonNode response) {
        if (response == null || !response.isObject()) return false;
        JsonNode answer = response.get("answer");
        if (answer == null || !answer.isTextual() || answer.asText().isBlank()) return false;
        JsonNode outcome = response.get("terminalOutcome");
        if (outcome == null) return true;
        if (!outcome.isObject()
                || outcome.path("version").asInt(-1) != 1
                || !outcome.path("status").isTextual()
                || !outcome.path("reason").isTextual()
                || !outcome.path("retryable").isBoolean()) return false;
        String status = outcome.path("status").asText();
        String reason = outcome.path("reason").asText();
        Set<String> reasons = REASONS.get(status);
        if (reasons == null || !reasons.contains(reason)) return false;
        boolean retryable = outcome.path("retryable").asBoolean();
        return reason.equals("PARTIAL_EVIDENCE") || retryable == reason.equals("TEMPORARY_FAILURE");
    }
}
