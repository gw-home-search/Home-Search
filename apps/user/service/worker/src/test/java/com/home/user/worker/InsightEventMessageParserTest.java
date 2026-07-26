package com.home.user.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

class InsightEventMessageParserTest {

    private final InsightEventMessageParser parser = new InsightEventMessageParser(new ObjectMapper());

    @Test
    @DisplayName("InsightPublished v1 plain JSON envelope를 application event로 변환한다")
    void parsesInsightPublished() {
        var event = parser.parse(message("""
                "eventType":"InsightPublished",
                "aggregateType":"InsightSnapshot",
                "payload":{
                  "snapshotId":"44444444-aaaa-4444-8444-444444444444",
                  "insightKind":"ROLLING_7D",
                  "scopeType":"SIDO",
                  "regionCode":"11",
                  "dataCutoff":"2026-07-24T03:00:00Z"
                }
                """));

        assertThat(event.eventId()).isEqualTo(UUID.fromString("44444444-4444-4444-8444-444444444444"));
        assertThat(event.eventType()).isEqualTo("InsightPublished");
        assertThat(event.insightKind()).isEqualTo("ROLLING_7D");
        assertThat(event.scopeType()).isEqualTo("SIDO");
        assertThat(event.regionCode()).isEqualTo("11");
    }

    @Test
    @DisplayName("NewsSnapshotPublished는 insightKind 없이 변환한다")
    void parsesNewsSnapshotPublished() {
        var event = parser.parse(message("""
                "eventType":"NewsSnapshotPublished",
                "aggregateType":"NewsSnapshot",
                "payload":{
                  "snapshotId":"44444444-aaaa-4444-8444-444444444444",
                  "scopeType":"NATIONWIDE",
                  "regionCode":null,
                  "dataCutoff":"2026-07-24T03:00:00Z"
                }
                """));

        assertThat(event.eventType()).isEqualTo("NewsSnapshotPublished");
        assertThat(event.insightKind()).isNull();
        assertThat(event.regionCode()).isNull();
    }

    @Test
    @DisplayName("unknown field와 contract metadata 불일치는 DLQ 대상 예외로 거부한다")
    void rejectsUnknownOrMismatchedContractFields() {
        String unknownField = message("""
                "eventType":"InsightPublished",
                "aggregateType":"InsightSnapshot",
                "email":"must-not-appear@example.com",
                "payload":{
                  "snapshotId":"44444444-aaaa-4444-8444-444444444444",
                  "insightKind":"ROLLING_7D",
                  "scopeType":"NATIONWIDE",
                  "regionCode":null,
                  "dataCutoff":"2026-07-24T03:00:00Z"
                }
                """);
        String wrongProducer = message("""
                "eventType":"InsightPublished",
                "aggregateType":"InsightSnapshot",
                "payload":{
                  "snapshotId":"44444444-aaaa-4444-8444-444444444444",
                  "insightKind":"ROLLING_7D",
                  "scopeType":"NATIONWIDE",
                  "regionCode":null,
                  "dataCutoff":"2026-07-24T03:00:00Z"
                }
                """).replace("\"producer\":\"property-data\"", "\"producer\":\"user-service\"");

        assertThatThrownBy(() -> parser.parse(unknownField)).isInstanceOf(InvalidInsightEventMessageException.class);
        assertThatThrownBy(() -> parser.parse(wrongProducer)).isInstanceOf(InvalidInsightEventMessageException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMessages")
    @DisplayName("schema-invalid envelope와 payload를 빠짐없이 거부한다")
    void rejectsSchemaInvalidMessages(String reason, String message) {
        assertThatThrownBy(() -> parser.parse(message))
                .as(reason)
                .isInstanceOf(InvalidInsightEventMessageException.class);
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidMessages() {
        String valid = message("""
                "eventType":"InsightPublished",
                "aggregateType":"InsightSnapshot",
                "payload":{
                  "snapshotId":"44444444-aaaa-4444-8444-444444444444",
                  "insightKind":"ROLLING_7D",
                  "scopeType":"NATIONWIDE",
                  "regionCode":null,
                  "dataCutoff":"2026-07-24T03:00:00Z"
                }
                """);
        String nonObjectPayload = message("""
                "eventType":"InsightPublished",
                "aggregateType":"InsightSnapshot",
                "payload":[]
                """);
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("blank", " "),
                org.junit.jupiter.params.provider.Arguments.of("invalid json", "{"),
                org.junit.jupiter.params.provider.Arguments.of("non-object", "[]"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "missing envelope field", valid.replace("\"traceId\":\"trace-444\"", "\"other\":\"x\"")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "wrong schema version", valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "wrong aggregate type", valid.replace("\"InsightSnapshot\"", "\"NewsSnapshot\"")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "unsupported event", valid.replace("\"InsightPublished\"", "\"UnknownPublished\"")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "invalid occurredAt", valid.replace("\"2026-07-24T03:03:00Z\"", "\"not-an-instant\"")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "invalid dataCutoff", valid.replace("\"2026-07-24T03:00:00Z\"", "\"not-an-instant\"")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "invalid event UUID",
                        valid.replace("\"44444444-4444-4444-8444-444444444444\"", "\"not-a-uuid\"")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "non-integer aggregate version",
                        valid.replace("\"aggregateVersion\":1", "\"aggregateVersion\":\"1\"")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "invalid aggregate id", valid.replace("\"insight-20260724\"", "\"contains whitespace\"")),
                org.junit.jupiter.params.provider.Arguments.of("payload is not object", nonObjectPayload),
                org.junit.jupiter.params.provider.Arguments.of(
                        "payload unknown field",
                        valid.replace(
                                "\"dataCutoff\":\"2026-07-24T03:00:00Z\"",
                                "\"dataCutoff\":\"2026-07-24T03:00:00Z\",\"email\":\"x\"")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "trace pattern", valid.replace("\"trace-444\"", "\"trace with space\"")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "causation type", valid.replace("\"causationId\":null", "\"causationId\":7")));
    }

    private static String message(String eventFields) {
        return """
                {
                  "eventId":"44444444-4444-4444-8444-444444444444",
                  %s,
                  "schemaVersion":1,
                  "occurredAt":"2026-07-24T03:03:00Z",
                  "producer":"property-data",
                  "aggregateId":"insight-20260724",
                  "aggregateVersion":1,
                  "correlationId":"run-20260724",
                  "causationId":null,
                  "traceId":"trace-444"
                }
                """.formatted(eventFields);
    }
}
