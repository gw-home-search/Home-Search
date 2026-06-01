package com.home.application.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplexMetadataRetryPolicyTest {

	private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

	private final ComplexMetadataRetryPolicy policy = new ComplexMetadataRetryPolicy();

	@Test
	@DisplayName("retry policy는 resolved와 ambiguous에 다음 시도를 예약하지 않는다")
	void terminalStatusesDoNotScheduleRetry() {
		assertThat(policy.nextAttemptAt(ComplexMetadataStatus.RESOLVED, null, 1, NOW)).isEmpty();
		assertThat(policy.nextAttemptAt(ComplexMetadataStatus.AMBIGUOUS, ComplexMetadataFailureKind.AMBIGUOUS, 1, NOW))
			.isEmpty();
	}

	@Test
	@DisplayName("retry policy는 transient failed를 짧은 backoff로 재시도한다")
	void transientFailuresUseShortBackoff() {
		assertThat(policy.nextAttemptAt(ComplexMetadataStatus.FAILED, ComplexMetadataFailureKind.TRANSIENT, 1, NOW))
			.contains(NOW.plusSeconds(86_400));
		assertThat(policy.nextAttemptAt(ComplexMetadataStatus.FAILED, ComplexMetadataFailureKind.TRANSIENT, 2, NOW))
			.contains(NOW.plusSeconds(259_200));
		assertThat(policy.nextAttemptAt(ComplexMetadataStatus.FAILED, ComplexMetadataFailureKind.TRANSIENT, 3, NOW))
			.contains(NOW.plusSeconds(604_800));
		assertThat(policy.nextAttemptAt(ComplexMetadataStatus.FAILED, ComplexMetadataFailureKind.TRANSIENT, 4, NOW))
			.contains(NOW.plusSeconds(2_592_000));
		assertThat(policy.nextAttemptAt(ComplexMetadataStatus.FAILED, ComplexMetadataFailureKind.TRANSIENT, 5, NOW))
			.isEmpty();
	}

	@Test
	@DisplayName("retry policy는 partial과 source missing unavailable을 긴 주기로 재검토한다")
	void partialAndSourceMissingUseLongBackoff() {
		assertThat(policy.nextAttemptAt(ComplexMetadataStatus.PARTIAL, null, 1, NOW))
			.contains(NOW.plusSeconds(1_209_600));
		assertThat(policy.nextAttemptAt(ComplexMetadataStatus.PARTIAL, null, 2, NOW))
			.contains(NOW.plusSeconds(2_592_000));
		assertThat(policy.nextAttemptAt(ComplexMetadataStatus.PARTIAL, null, 3, NOW))
			.contains(NOW.plusSeconds(7_776_000));
		assertThat(policy.nextAttemptAt(ComplexMetadataStatus.PARTIAL, null, 4, NOW)).isEmpty();

		assertThat(policy.nextAttemptAt(
			ComplexMetadataStatus.UNAVAILABLE,
			ComplexMetadataFailureKind.SOURCE_MISSING,
			1,
			NOW
		)).contains(NOW.plusSeconds(2_592_000));
		assertThat(policy.nextAttemptAt(
			ComplexMetadataStatus.UNAVAILABLE,
			ComplexMetadataFailureKind.SOURCE_MISSING,
			2,
			NOW
		)).contains(NOW.plusSeconds(7_776_000));
		assertThat(policy.nextAttemptAt(
			ComplexMetadataStatus.UNAVAILABLE,
			ComplexMetadataFailureKind.SOURCE_MISSING,
			3,
			NOW
		)).isEmpty();
	}

	@Test
	@DisplayName("retry policy는 입력 부족과 영구 실패를 자동 재시도하지 않는다")
	void inputInsufficientAndPermanentFailuresDoNotRetry() {
		assertThat(policy.nextAttemptAt(
			ComplexMetadataStatus.UNAVAILABLE,
			ComplexMetadataFailureKind.INPUT_INSUFFICIENT,
			1,
			NOW
		)).isEmpty();
		assertThat(policy.nextAttemptAt(ComplexMetadataStatus.FAILED, ComplexMetadataFailureKind.PERMANENT, 1, NOW))
			.isEmpty();
	}
}
