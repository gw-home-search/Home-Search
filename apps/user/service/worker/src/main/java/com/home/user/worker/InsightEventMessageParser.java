package com.home.user.worker;

import com.home.application.insight.PublishedInsightEvent;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class InsightEventMessageParser {

    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "eventId",
            "eventType",
            "schemaVersion",
            "occurredAt",
            "producer",
            "aggregateType",
            "aggregateId",
            "aggregateVersion",
            "correlationId",
            "causationId",
            "traceId",
            "payload");
    private static final Set<String> INSIGHT_PAYLOAD_FIELDS =
            Set.of("snapshotId", "insightKind", "scopeType", "regionCode", "dataCutoff");
    private static final Set<String> NEWS_PAYLOAD_FIELDS =
            Set.of("snapshotId", "scopeType", "regionCode", "dataCutoff");

    private final ObjectMapper objectMapper;

    public InsightEventMessageParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PublishedInsightEvent parse(String message) {
        if (message == null || message.isBlank()) {
            throw new InvalidInsightEventMessageException("event message is required");
        }
        try {
            JsonNode envelope = objectMapper.readTree(message);
            requireObject(envelope, "envelope");
            requireExactFields(envelope, ENVELOPE_FIELDS, "envelope");

            String eventType = requiredText(envelope, "eventType");
            String aggregateType = requiredText(envelope, "aggregateType");
            validateEnvelopeMetadata(envelope, eventType, aggregateType);

            JsonNode payload = envelope.get("payload");
            requireObject(payload, "payload");
            Set<String> payloadFields =
                    switch (eventType) {
                        case "InsightPublished" -> INSIGHT_PAYLOAD_FIELDS;
                        case "NewsSnapshotPublished" -> NEWS_PAYLOAD_FIELDS;
                        default -> throw invalid("unsupported eventType");
                    };
            requireExactFields(payload, payloadFields, "payload");
            parseInstant(requiredText(payload, "dataCutoff"), "payload.dataCutoff");

            return new PublishedInsightEvent(
                    parseUuid(requiredText(envelope, "eventId"), "eventId"),
                    eventType,
                    patternedText(envelope, "aggregateId", "[A-Za-z0-9._:-]{1,128}"),
                    requiredLong(envelope, "aggregateVersion"),
                    parseUuid(requiredText(payload, "snapshotId"), "payload.snapshotId"),
                    "InsightPublished".equals(eventType) ? requiredText(payload, "insightKind") : null,
                    requiredText(payload, "scopeType"),
                    nullableText(payload, "regionCode"));
        } catch (InvalidInsightEventMessageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidInsightEventMessageException("event message does not match the v1 contract", exception);
        } catch (Exception exception) {
            throw new InvalidInsightEventMessageException("event message JSON is invalid", exception);
        }
    }

    private static void validateEnvelopeMetadata(JsonNode envelope, String eventType, String aggregateType) {
        if (requiredLong(envelope, "schemaVersion") != 1) throw invalid("schemaVersion must be 1");
        if (!"property-data".equals(requiredText(envelope, "producer"))) {
            throw invalid("producer must be property-data");
        }
        String expectedAggregateType =
                switch (eventType) {
                    case "InsightPublished" -> "InsightSnapshot";
                    case "NewsSnapshotPublished" -> "NewsSnapshot";
                    default -> throw invalid("unsupported eventType");
                };
        if (!expectedAggregateType.equals(aggregateType)) throw invalid("aggregateType does not match eventType");
        parseInstant(requiredText(envelope, "occurredAt"), "occurredAt");
        boundedText(envelope, "correlationId", 128, true);
        boundedNullableText(envelope, "causationId", 128);
        patternedText(envelope, "traceId", "[A-Za-z0-9._:-]{1,128}");
    }

    private static void requireObject(JsonNode node, String location) {
        if (node == null || !node.isObject()) throw invalid(location + " must be an object");
    }

    private static void requireExactFields(JsonNode node, Set<String> expected, String location) {
        Set<String> actual = new HashSet<>(node.propertyNames());
        if (!actual.equals(expected)) throw invalid(location + " fields do not match the contract");
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid(field + " must be non-blank text");
        }
        return value.asText();
    }

    private static String boundedText(JsonNode node, String field, int maxLength, boolean allowEmpty) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw invalid(field + " must be text");
        String text = value.asText();
        if ((!allowEmpty && text.isBlank()) || text.length() > maxLength) {
            throw invalid(field + " is outside the text contract");
        }
        return text;
    }

    private static String boundedNullableText(JsonNode node, String field, int maxLength) {
        JsonNode value = node.get(field);
        if (value == null) throw invalid(field + " is required");
        if (value.isNull()) return null;
        if (!value.isTextual() || value.asText().length() > maxLength) {
            throw invalid(field + " must be bounded text or null");
        }
        return value.asText();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.asText().isBlank()) throw invalid(field + " must be text or null");
        return value.asText();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(field + " must be an integer");
        }
        return value.asLong();
    }

    private static String patternedText(JsonNode node, String field, String pattern) {
        String value = requiredText(node, field);
        if (!value.matches(pattern)) throw invalid(field + " does not match the contract");
        return value;
    }

    private static UUID parseUuid(String value, String field) {
        try {
            if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                throw new IllegalArgumentException("UUID must use canonical text form");
            }
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidInsightEventMessageException(field + " must be a UUID", exception);
        }
    }

    private static Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new InvalidInsightEventMessageException(field + " must be an RFC 3339 instant", exception);
        }
    }

    private static InvalidInsightEventMessageException invalid(String message) {
        return new InvalidInsightEventMessageException(message);
    }
}
