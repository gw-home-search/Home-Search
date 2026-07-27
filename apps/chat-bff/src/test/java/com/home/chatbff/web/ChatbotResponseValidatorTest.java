package com.home.chatbff.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChatbotResponseValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatbotResponseValidator validator = new ChatbotResponseValidator();

    @Test
    void acceptsLegacyOmissionAndSupportedTerminalOutcome() throws Exception {
        assertThat(validator.isValid(objectMapper.readTree("{\"answer\":\"답변\"}")))
                .isTrue();
        assertThat(validator.isValid(objectMapper.readTree("""
                {"answer":"답변","terminalOutcome":{"version":1,"status":"PARTIAL",
                "reason":"PARTIAL_EVIDENCE","retryable":true}}
                """))).isTrue();
    }

    @Test
    void rejectsUnknownCombinationAndRetryabilityMismatch() throws Exception {
        assertThat(validator.isValid(objectMapper.readTree("""
                {"answer":"답변","terminalOutcome":{"version":1,"status":"ANSWERED",
                "reason":"TEMPORARY_FAILURE","retryable":true}}
                """))).isFalse();
        assertThat(validator.isValid(objectMapper.readTree("""
                {"answer":"답변","terminalOutcome":{"version":1,"status":"UNAVAILABLE",
                "reason":"TEMPORARY_FAILURE","retryable":false}}
                """))).isFalse();
    }
}
