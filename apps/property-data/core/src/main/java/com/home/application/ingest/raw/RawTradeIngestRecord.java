package com.home.application.ingest.raw;

import com.home.domain.ingest.raw.RawTradeIngestStatus;
import com.home.domain.ingest.run.ExecutionCorrelationId;
import com.home.domain.ingest.source.IngestSource;
import com.home.domain.ingest.source.IngestSourceKey;
import com.home.domain.ingest.source.RtmsSourceDate;
import com.home.ingestcore.rtms.RtmsDealMonth;
import com.home.ingestcore.rtms.RtmsLawdCode;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 외부 원천 payload와 ingest 처리 상태를 보존하는 raw trade evidence record입니다.
 */
public record RawTradeIngestRecord(
        Long id,
        String source,
        String sourceKey,
        String lawdCd,
        String dealYmd,
        Integer pageNo,
        String payload,
        String payloadHash,
        RawTradeIngestStatus status,
        String failureReason,
        Instant createdAt,
        Instant processedAt,
        ExecutionCorrelationId executionCorrelationId,
        String registrationDateRaw,
        LocalDate registrationDate,
        String cancellationDateRaw,
        LocalDate cancellationDate) {

    public RawTradeIngestRecord(
            Long id,
            String source,
            String sourceKey,
            String lawdCd,
            String dealYmd,
            Integer pageNo,
            String payload,
            String payloadHash,
            RawTradeIngestStatus status,
            String failureReason,
            Instant createdAt,
            Instant processedAt) {
        this(
                id,
                source,
                sourceKey,
                lawdCd,
                dealYmd,
                pageNo,
                payload,
                payloadHash,
                status,
                failureReason,
                createdAt,
                processedAt,
                null,
                null,
                null,
                null,
                null);
    }

    public RawTradeIngestRecord(
            Long id,
            String source,
            String sourceKey,
            String lawdCd,
            String dealYmd,
            Integer pageNo,
            String payload,
            String payloadHash,
            RawTradeIngestStatus status,
            String failureReason,
            Instant createdAt,
            Instant processedAt,
            ExecutionCorrelationId executionCorrelationId) {
        this(
                id,
                source,
                sourceKey,
                lawdCd,
                dealYmd,
                pageNo,
                payload,
                payloadHash,
                status,
                failureReason,
                createdAt,
                processedAt,
                executionCorrelationId,
                null,
                null,
                null,
                null);
    }

    public static RawTradeIngestRecord received(
            String source,
            String sourceKey,
            String lawdCd,
            String dealYmd,
            Integer pageNo,
            String payload,
            String payloadHash) {
        return received(source, sourceKey, lawdCd, dealYmd, pageNo, payload, payloadHash, null);
    }

    public static RawTradeIngestRecord received(
            String source,
            String sourceKey,
            String lawdCd,
            String dealYmd,
            Integer pageNo,
            String payload,
            String payloadHash,
            ExecutionCorrelationId executionCorrelationId) {
        return received(
                source, sourceKey, lawdCd, dealYmd, pageNo, payload, payloadHash, executionCorrelationId, null, null);
    }

    public static RawTradeIngestRecord received(
            String source,
            String sourceKey,
            String lawdCd,
            String dealYmd,
            Integer pageNo,
            String payload,
            String payloadHash,
            ExecutionCorrelationId executionCorrelationId,
            String registrationDateRaw,
            String cancellationDateRaw) {
        RtmsSourceDate registration = RtmsSourceDate.parse(registrationDateRaw);
        RtmsSourceDate cancellation = RtmsSourceDate.parse(cancellationDateRaw);
        return new RawTradeIngestRecord(
                null,
                source,
                sourceKey,
                lawdCd,
                dealYmd,
                pageNo,
                payload,
                payloadHash,
                RawTradeIngestStatus.RECEIVED,
                null,
                Instant.now(),
                null,
                executionCorrelationId,
                registration.rawValue(),
                registration.value(),
                cancellation.rawValue(),
                cancellation.value());
    }

    public RawTradeIngestRecord {
        source = IngestSource.of(source).value();
        sourceKey = IngestSourceKey.of(sourceKey).value();
        lawdCd = RtmsLawdCode.of(lawdCd).value();
        dealYmd = RtmsDealMonth.of(dealYmd).value();
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        payload = trimToNull(payload);
        payloadHash = trimToNull(payloadHash);
        failureReason = trimToNull(failureReason);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public RawTradeIngestRecord withId(Long id) {
        return new RawTradeIngestRecord(
                id,
                source,
                sourceKey,
                lawdCd,
                dealYmd,
                pageNo,
                payload,
                payloadHash,
                status,
                failureReason,
                createdAt,
                processedAt,
                executionCorrelationId,
                registrationDateRaw,
                registrationDate,
                cancellationDateRaw,
                cancellationDate);
    }

    public RawTradeIngestRecord withStatus(RawTradeIngestStatus status, String failureReason) {
        return new RawTradeIngestRecord(
                id,
                source,
                sourceKey,
                lawdCd,
                dealYmd,
                pageNo,
                payload,
                payloadHash,
                status,
                failureReason,
                createdAt,
                Instant.now(),
                executionCorrelationId,
                registrationDateRaw,
                registrationDate,
                cancellationDateRaw,
                cancellationDate);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
