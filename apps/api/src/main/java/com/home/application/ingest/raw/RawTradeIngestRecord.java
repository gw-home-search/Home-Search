package com.home.application.ingest.raw;

import java.time.Instant;

import com.home.domain.ingest.rtms.RtmsDealMonth;
import com.home.domain.ingest.rtms.RtmsLawdCode;
import com.home.domain.ingest.source.IngestSource;
import com.home.domain.ingest.source.IngestSourceKey;
import com.home.domain.ingest.raw.RawTradeIngestStatus;

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
	Instant processedAt
) {

	public static RawTradeIngestRecord received(
		String source,
		String sourceKey,
		String lawdCd,
		String dealYmd,
		Integer pageNo,
		String payload,
		String payloadHash
	) {
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
			null
		);
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
			processedAt
		);
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
			Instant.now()
		);
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static String trimToNull(String value) {
		return hasText(value) ? value.trim() : null;
	}
}
