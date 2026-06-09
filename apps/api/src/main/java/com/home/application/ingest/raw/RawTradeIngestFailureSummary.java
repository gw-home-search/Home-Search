package com.home.application.ingest.raw;

import com.home.domain.ingest.raw.RawTradeIngestStatus;
import com.home.domain.ingest.rtms.RtmsDealMonth;
import com.home.domain.ingest.rtms.RtmsLawdCode;
import com.home.domain.ingest.source.IngestSource;

/**
 * 운영 조회용 raw ingest 실패 요약입니다. Raw payload와 source_key는 포함하지 않습니다.
 */
public record RawTradeIngestFailureSummary(
	RawTradeIngestStatus status,
	String source,
	String lawdCd,
	String dealYmd,
	String failureReason,
	long count
) {

	public RawTradeIngestFailureSummary {
		if (status == null) {
			throw new IllegalArgumentException("status is required");
		}
		source = IngestSource.of(source).value();
		lawdCd = RtmsLawdCode.of(lawdCd).value();
		dealYmd = RtmsDealMonth.of(dealYmd).value();
		if (count < 1) {
			throw new IllegalArgumentException("count must be positive");
		}
		failureReason = trimToNull(failureReason);
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static String trimToNull(String value) {
		return hasText(value) ? value.trim() : null;
	}
}
