package com.home.chatbff.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ChatbotResponseValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatbotResponseValidator validator = new ChatbotResponseValidator();

    @Test
    void acceptsLegacyOmissionAndSupportedTerminalOutcome() throws Exception {
        assertThat(validator.isValid(objectMapper.readTree("""
                {"success":true,"status":"success","answer":"답변","citations":[],
                "dataAsOf":null,"limitations":[],"evidenceSummary":{"status":"supported",
                "capabilities":[],"factCount":0,"citationCount":0}}
                """))).isTrue();
        assertThat(validator.isValid(objectMapper.readTree("""
                {"success":true,"status":"partial_success","answer":"답변","citations":[],
                "dataAsOf":null,"limitations":[],"evidenceSummary":{"status":"partial",
                "capabilities":[],"factCount":0,"citationCount":0},
                "terminalOutcome":{"version":1,"status":"PARTIAL",
                "reason":"PARTIAL_EVIDENCE","retryable":true}}
                """))).isTrue();
    }

    @Test
    void rejectsAnswerOnlyResponseThatWebCannotParse() throws Exception {
        assertThat(validator.isValid(objectMapper.readTree("{\"answer\":\"답변\"}")))
                .isFalse();
    }

    @Test
    void rejectsCitationAndDateShapesThatWebCannotParse() throws Exception {
        assertThat(validator.isValid(objectMapper.readTree("""
                {"success":true,"status":"success","answer":"답변","citations":[{}],
                "dataAsOf":"not-a-date","limitations":[],
                "evidenceSummary":{"status":"supported","capabilities":[],
                "factCount":1,"citationCount":1}}
                """))).isFalse();
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

    @Test
    void acceptsCompleteCitationAndEverySupportedTerminalMapping() throws Exception {
        assertThat(validator.isValid(validResponse())).isTrue();

        ObjectNode legacy = validResponse();
        legacy.remove("terminalOutcome");
        assertThat(validator.isValid(legacy)).isTrue();

        ObjectNode partial = validResponse();
        partial.put("status", "partial_success");
        partial.withObject("evidenceSummary").put("status", "partial");
        setOutcome(partial, "PARTIAL", "PARTIAL_EVIDENCE", true);
        assertThat(validator.isValid(partial)).isTrue();

        ObjectNode clarification = failedResponse("CLARIFICATION", "AMBIGUOUS_ENTITY", false);
        assertThat(validator.isValid(clarification)).isTrue();

        ObjectNode insufficient = failedResponse("UNAVAILABLE", "INSUFFICIENT_EVIDENCE", false);
        assertThat(validator.isValid(insufficient)).isTrue();

        ObjectNode outOfScope = failedResponse("UNAVAILABLE", "OUT_OF_SCOPE", false);
        assertThat(validator.isValid(outOfScope)).isTrue();

        ObjectNode temporary = failedResponse("UNAVAILABLE", "TEMPORARY_FAILURE", true);
        assertThat(validator.isValid(temporary)).isTrue();
    }

    @Test
    void acceptsNullableCitationMetadata() throws Exception {
        ObjectNode response = validResponse();
        ObjectNode citation = firstCitation(response);
        citation.putNull("sourceUrl");
        citation.putNull("datasetVersion");
        citation.putNull("dataAsOf");
        citation.putNull("observedAt");
        response.putNull("dataAsOf");

        assertThat(validator.isValid(response)).isTrue();
    }

    @Test
    void rejectsMalformedTopLevelContractFields() throws Exception {
        assertThat(validator.isValid(null)).isFalse();
        assertThat(validator.isValid(objectMapper.createArrayNode())).isFalse();

        assertInvalid(response -> response.remove("answer"));
        assertInvalid(response -> response.put("answer", 1));
        assertInvalid(response -> response.put("answer", " "));
        assertInvalid(response -> response.put("answer", "a".repeat(20_001)));
        assertInvalid(response -> response.remove("success"));
        assertInvalid(response -> response.put("success", "true"));
        assertInvalid(response -> response.remove("status"));
        assertInvalid(response -> response.put("status", 1));
        assertInvalid(response -> response.put("status", "unknown"));
        assertInvalid(response -> response.put("success", false));
        assertInvalid(response -> response.remove("citations"));
        assertInvalid(response -> response.put("citations", "invalid"));
        assertInvalid(response -> {
            var citations = objectMapper.createArrayNode();
            for (int index = 0; index < 101; index++) citations.addObject();
            response.set("citations", citations);
        });
        assertInvalid(response -> response.remove("dataAsOf"));
        assertInvalid(response -> response.put("dataAsOf", 1));
        assertInvalid(response -> response.put("dataAsOf", "2026-99-99"));
        assertInvalid(response -> response.remove("limitations"));
        assertInvalid(response -> response.put("limitations", "invalid"));
        assertInvalid(response -> {
            var limitations = objectMapper.createArrayNode();
            for (int index = 0; index < 51; index++) limitations.add("한계");
            response.set("limitations", limitations);
        });
        assertInvalid(response -> response.withArray("limitations").add(1));
        assertInvalid(response -> response.withArray("limitations").add("a".repeat(2_001)));
        assertInvalid(response -> response.withArray("limitations").add(" "));
    }

    @Test
    void rejectsMalformedCitationFields() throws Exception {
        assertInvalid(response -> response.withArray("citations").set(0, objectMapper.createArrayNode()));
        assertInvalid(response -> firstCitation(response).remove("citationId"));
        assertInvalid(response -> firstCitation(response).put("citationId", "bad id"));
        assertInvalid(response -> firstCitation(response).remove("sourceId"));
        assertInvalid(response -> firstCitation(response).put("sourceId", "bad id"));
        assertInvalid(response -> firstCitation(response).remove("sourceName"));
        assertInvalid(response -> firstCitation(response).put("sourceName", " "));
        assertInvalid(response -> firstCitation(response).put("sourceName", "a".repeat(201)));
        assertInvalid(response -> firstCitation(response).remove("sourceUrl"));
        assertInvalid(response -> firstCitation(response).put("sourceUrl", 1));
        assertInvalid(response -> firstCitation(response).put("sourceUrl", "a".repeat(2_001)));
        assertInvalid(response -> firstCitation(response).put("sourceUrl", "http://example.com"));
        assertInvalid(response -> firstCitation(response).put("sourceUrl", "https://user@example.com"));
        assertInvalid(response -> firstCitation(response).put("sourceUrl", "https:///missing-host"));
        assertInvalid(response -> firstCitation(response).put("sourceUrl", "https://exa mple.com"));
        assertInvalid(response -> firstCitation(response).put("evidenceGrade", "E"));
        assertInvalid(response -> firstCitation(response).remove("datasetVersion"));
        assertInvalid(response -> firstCitation(response).put("datasetVersion", "bad id"));
        assertInvalid(response -> firstCitation(response).remove("dataAsOf"));
        assertInvalid(response -> firstCitation(response).put("dataAsOf", 1));
        assertInvalid(response -> firstCitation(response).put("dataAsOf", "2026-02-30"));
        assertInvalid(response -> firstCitation(response).remove("observedAt"));
        assertInvalid(response -> firstCitation(response).put("observedAt", 1));
        assertInvalid(response -> firstCitation(response).put("observedAt", "not-a-timestamp"));
        assertInvalid(response -> firstCitation(response).remove("factIds"));
        assertInvalid(response -> firstCitation(response).put("factIds", "fact-1"));
        assertInvalid(response -> firstCitation(response).set("factIds", objectMapper.createArrayNode()));
        assertInvalid(response -> {
            var factIds = objectMapper.createArrayNode();
            for (int index = 0; index < 101; index++) factIds.add("fact-" + index);
            firstCitation(response).set("factIds", factIds);
        });
        assertInvalid(response -> firstCitation(response).withArray("factIds").set(0, "bad id"));
    }

    @Test
    void rejectsMalformedEvidenceSummary() throws Exception {
        assertInvalid(response -> response.remove("evidenceSummary"));
        assertInvalid(response -> response.put("evidenceSummary", "invalid"));
        assertInvalid(response -> response.withObject("evidenceSummary").put("status", "unknown"));
        assertInvalid(response -> response.withObject("evidenceSummary").remove("capabilities"));
        assertInvalid(response -> response.withObject("evidenceSummary").put("capabilities", "invalid"));
        assertInvalid(response -> {
            var capabilities = objectMapper.createArrayNode();
            for (int index = 0; index < 21; index++) capabilities.add("capability-" + index);
            response.withObject("evidenceSummary").set("capabilities", capabilities);
        });
        assertInvalid(response ->
                response.withObject("evidenceSummary").withArray("capabilities").set(0, "bad id"));
        assertInvalid(response -> response.withObject("evidenceSummary").remove("factCount"));
        assertInvalid(response -> response.withObject("evidenceSummary").put("factCount", 1.5));
        assertInvalid(response -> response.withObject("evidenceSummary").put("factCount", 2_147_483_648L));
        assertInvalid(response -> response.withObject("evidenceSummary").put("factCount", 0));
        assertInvalid(response -> response.withObject("evidenceSummary").remove("citationCount"));
        assertInvalid(response -> response.withObject("evidenceSummary").put("citationCount", 1.5));
        assertInvalid(response -> response.withObject("evidenceSummary").put("citationCount", 2_147_483_648L));
        assertInvalid(response -> response.withObject("evidenceSummary").put("citationCount", 0));
    }

    @Test
    void rejectsMalformedTerminalOutcomeAndLegacyMappings() throws Exception {
        assertInvalid(response -> response.put("terminalOutcome", "invalid"));
        assertInvalid(response -> response.withObject("terminalOutcome").put("version", 2));
        assertInvalid(response -> response.withObject("terminalOutcome").put("status", 1));
        assertInvalid(response -> response.withObject("terminalOutcome").put("reason", 1));
        assertInvalid(response -> response.withObject("terminalOutcome").put("retryable", "false"));
        assertInvalid(response -> response.withObject("terminalOutcome").put("status", "UNKNOWN"));
        assertInvalid(response -> response.withObject("terminalOutcome").put("reason", "OUT_OF_SCOPE"));
        assertInvalid(response -> response.put("status", "partial_success"));
        assertInvalid(response -> {
            response.put("success", false);
            response.put("status", "failed");
        });
        assertInvalid(response -> setOutcome(response, "UNAVAILABLE", "TEMPORARY_FAILURE", false));
        assertInvalid(response -> setOutcome(response, "ANSWERED", "COMPLETED", true));
    }

    private ObjectNode validResponse() throws Exception {
        return (ObjectNode) objectMapper.readTree("""
                {"success":true,"status":"success","answer":"검증된 답변",
                "citations":[{"citationId":"citation-1","sourceId":"source-1",
                "sourceName":"공공 데이터","sourceUrl":"https://example.com/data",
                "evidenceGrade":"A","datasetVersion":"v1","dataAsOf":"2026-07-27",
                "observedAt":"2026-07-27T12:00:00+09:00","factIds":["fact-1"]}],
                "dataAsOf":"2026-07-27","limitations":["공개 데이터 기준"],
                "evidenceSummary":{"status":"supported","capabilities":["recent_trade_lookup"],
                "factCount":1,"citationCount":1},
                "terminalOutcome":{"version":1,"status":"ANSWERED",
                "reason":"COMPLETED","retryable":false}}
                """);
    }

    private ObjectNode failedResponse(String status, String reason, boolean retryable) throws Exception {
        ObjectNode response = validResponse();
        response.put("success", false);
        response.put("status", "failed");
        setOutcome(response, status, reason, retryable);
        return response;
    }

    private void assertInvalid(Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode response = validResponse();
        mutation.accept(response);
        assertThat(validator.isValid(response)).isFalse();
    }

    private ObjectNode firstCitation(ObjectNode response) {
        return (ObjectNode) response.withArray("citations").get(0);
    }

    private void setOutcome(ObjectNode response, String status, String reason, boolean retryable) {
        ObjectNode outcome = response.withObject("terminalOutcome");
        outcome.put("version", 1);
        outcome.put("status", status);
        outcome.put("reason", reason);
        outcome.put("retryable", retryable);
    }
}
