package com.home.domain.ingest.raw;

/**
 * raw ingest row에 저장할 status와 failure reason 조합을 나타낸다.
 * 저장 status enum과 failure_reason 문자열 값은 운영 evidence 계약이므로 각 factory에서 명시적으로 고정한다.
 */
public record RawTradeIngestTransition(
	RawTradeIngestStatus status,
	String failureReason
) {

	public RawTradeIngestTransition {
		if (status == null) {
			throw new IllegalArgumentException("status is required");
		}
		failureReason = trimToNull(failureReason);
	}

	public static RawTradeIngestTransition normalized() {
		return new RawTradeIngestTransition(RawTradeIngestStatus.NORMALIZED, null);
	}

	public static RawTradeIngestTransition sourceKeyDuplicate() {
		return new RawTradeIngestTransition(
			RawTradeIngestStatus.DUPLICATE,
			RawTradeIngestFailureReason.SOURCE_KEY_DUPLICATE.value()
		);
	}

	public static RawTradeIngestTransition fallbackIdentityDuplicate() {
		return new RawTradeIngestTransition(
			RawTradeIngestStatus.DUPLICATE,
			RawTradeIngestFailureReason.FALLBACK_IDENTITY_DUPLICATE.value()
		);
	}

	public static RawTradeIngestTransition canceledSourceKey() {
		return new RawTradeIngestTransition(
			RawTradeIngestStatus.CANCELED,
			RawTradeIngestFailureReason.CANCELED_SOURCE_KEY.value()
		);
	}

	public static RawTradeIngestTransition rematchSourceKeyDuplicate() {
		return new RawTradeIngestTransition(
			RawTradeIngestStatus.DUPLICATE,
			RawTradeIngestFailureReason.REMATCH_SOURCE_KEY_DUPLICATE.value()
		);
	}

	public static RawTradeIngestTransition rematchFallbackIdentityDuplicate() {
		return new RawTradeIngestTransition(
			RawTradeIngestStatus.DUPLICATE,
			RawTradeIngestFailureReason.REMATCH_FALLBACK_IDENTITY_DUPLICATE.value()
		);
	}

	public static RawTradeIngestTransition parseFailed(String failureReason) {
		return new RawTradeIngestTransition(RawTradeIngestStatus.PARSE_FAILED, failureReason);
	}

	public static RawTradeIngestTransition matchFailed(String failureReason) {
		return new RawTradeIngestTransition(RawTradeIngestStatus.MATCH_FAILED, failureReason);
	}

	private static String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
