package com.home.chatbff.web;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
final class ChatbotResponseValidator {
    private static final Set<String> LEGACY_STATUSES = Set.of("success", "partial_success", "failed");
    private static final Set<String> EVIDENCE_STATUSES = Set.of("supported", "partial", "unavailable");
    private static final Set<String> EVIDENCE_GRADES = Set.of("A", "B", "C", "D");
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$");
    private static final Map<String, Set<String>> REASONS = Map.of(
            "ANSWERED", Set.of("COMPLETED"),
            "PARTIAL", Set.of("PARTIAL_EVIDENCE"),
            "CLARIFICATION", Set.of("AMBIGUOUS_ENTITY"),
            "UNAVAILABLE", Set.of("INSUFFICIENT_EVIDENCE", "OUT_OF_SCOPE", "TEMPORARY_FAILURE"));

    boolean isValid(JsonNode response) {
        if (response == null || !response.isObject()) return false;
        JsonNode answer = response.get("answer");
        if (answer == null
                || !answer.isTextual()
                || answer.asText().isBlank()
                || answer.asText().length() > 20_000) return false;
        JsonNode success = response.get("success");
        JsonNode legacyStatus = response.get("status");
        if (success == null
                || !success.isBoolean()
                || legacyStatus == null
                || !legacyStatus.isTextual()
                || !LEGACY_STATUSES.contains(legacyStatus.asText())
                || success.asBoolean() != !legacyStatus.asText().equals("failed")) return false;
        JsonNode citations = response.get("citations");
        JsonNode dataAsOf = response.get("dataAsOf");
        JsonNode limitations = response.get("limitations");
        JsonNode evidence = response.get("evidenceSummary");
        if (citations == null
                || !citations.isArray()
                || citations.size() > 100
                || !validCitations(citations)
                || dataAsOf == null
                || !validOptionalDate(dataAsOf)
                || limitations == null
                || !limitations.isArray()
                || !validTextArray(limitations, 50, 2_000, false)
                || !validEvidenceSummary(evidence, citations)) return false;
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
        if (!matchesLegacyStatus(status, success.asBoolean(), legacyStatus.asText())) return false;
        boolean retryable = outcome.path("retryable").asBoolean();
        return reason.equals("PARTIAL_EVIDENCE") || retryable == reason.equals("TEMPORARY_FAILURE");
    }

    private boolean validCitations(JsonNode citations) {
        for (JsonNode citation : citations) {
            if (!citation.isObject()
                    || !validIdentifier(citation.get("citationId"))
                    || !validIdentifier(citation.get("sourceId"))
                    || !validText(citation.get("sourceName"), 200)
                    || !validSourceUrl(citation.get("sourceUrl"))
                    || !EVIDENCE_GRADES.contains(citation.path("evidenceGrade").asText(""))
                    || !validOptionalIdentifier(citation.get("datasetVersion"))
                    || !validOptionalDate(citation.get("dataAsOf"))
                    || !validOptionalTimestamp(citation.get("observedAt"))
                    || !validIdentifierArray(citation.get("factIds"), 1, 100)) return false;
        }
        return true;
    }

    private boolean validEvidenceSummary(JsonNode evidence, JsonNode citations) {
        Set<String> factIds = new HashSet<>();
        for (JsonNode citation : citations) {
            for (JsonNode factId : citation.path("factIds")) factIds.add(factId.asText());
        }
        return evidence != null
                && evidence.isObject()
                && EVIDENCE_STATUSES.contains(evidence.path("status").asText(""))
                && validIdentifierArray(evidence.get("capabilities"), 0, 20)
                && evidence.path("factCount").isIntegralNumber()
                && evidence.path("factCount").canConvertToInt()
                && evidence.path("factCount").asInt(-1) == factIds.size()
                && evidence.path("citationCount").isIntegralNumber()
                && evidence.path("citationCount").canConvertToInt()
                && evidence.path("citationCount").asInt(-1) == citations.size();
    }

    private boolean validIdentifierArray(JsonNode value, int minimum, int maximum) {
        if (value == null || !value.isArray() || value.size() < minimum || value.size() > maximum) {
            return false;
        }
        for (JsonNode item : value) if (!validIdentifier(item)) return false;
        return true;
    }

    private boolean validTextArray(JsonNode value, int maximumItems, int maximumLength, boolean blankAllowed) {
        if (value.size() > maximumItems) return false;
        for (JsonNode item : value) {
            if (!item.isTextual()
                    || item.asText().length() > maximumLength
                    || (!blankAllowed && item.asText().isBlank())) return false;
        }
        return true;
    }

    private boolean validIdentifier(JsonNode value) {
        return value != null
                && value.isTextual()
                && IDENTIFIER.matcher(value.asText()).matches();
    }

    private boolean validOptionalIdentifier(JsonNode value) {
        return value != null && (value.isNull() || validIdentifier(value));
    }

    private boolean validText(JsonNode value, int maximumLength) {
        return value != null
                && value.isTextual()
                && !value.asText().isBlank()
                && value.asText().length() <= maximumLength;
    }

    private boolean validOptionalDate(JsonNode value) {
        if (value == null) return false;
        if (value.isNull()) return true;
        if (!value.isTextual()) return false;
        try {
            LocalDate.parse(value.asText());
            return true;
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private boolean validOptionalTimestamp(JsonNode value) {
        if (value == null) return false;
        if (value.isNull()) return true;
        if (!value.isTextual()) return false;
        try {
            OffsetDateTime.parse(value.asText());
            return true;
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private boolean validSourceUrl(JsonNode value) {
        if (value == null) return false;
        if (value.isNull()) return true;
        if (!value.isTextual() || value.asText().length() > 2_000) return false;
        try {
            URI uri = URI.create(value.asText());
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getRawUserInfo() == null
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean matchesLegacyStatus(String status, boolean success, String legacyStatus) {
        return switch (status) {
            case "ANSWERED" -> success && legacyStatus.equals("success");
            case "PARTIAL" -> success && legacyStatus.equals("partial_success");
            case "CLARIFICATION", "UNAVAILABLE" -> !success && legacyStatus.equals("failed");
            default -> false;
        };
    }
}
