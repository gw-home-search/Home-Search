package com.home.domain.complex.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplexMetadataStatusTest {

	@Test
	@DisplayName("complex metadata status는 metadata payload 필요 여부를 직접 구분한다")
	void statusOwnsMetadataPayloadRequirement() {
		assertThat(ComplexMetadataStatus.RESOLVED.requiresMetadataPayload()).isTrue();
		assertThat(ComplexMetadataStatus.PARTIAL.requiresMetadataPayload()).isTrue();
		assertThat(ComplexMetadataStatus.AMBIGUOUS.requiresMetadataPayload()).isFalse();
		assertThat(ComplexMetadataStatus.UNAVAILABLE.requiresMetadataPayload()).isFalse();
		assertThat(ComplexMetadataStatus.FAILED.requiresMetadataPayload()).isFalse();
		assertThat(ComplexMetadataStatus.PENDING.requiresMetadataPayload()).isFalse();

		assertThat(ComplexMetadataStatus.RESOLVED.requiresCompleteCriticalFields()).isTrue();
		assertThat(ComplexMetadataStatus.PARTIAL.requiresCompleteCriticalFields()).isFalse();
	}

	@Test
	@DisplayName("complex metadata failure kind는 retry 정책용 실패 의미를 직접 제공한다")
	void failureKindOwnsRetryMeaning() {
		assertThat(ComplexMetadataFailureKind.SOURCE_MISSING.isSourceMissing()).isTrue();
		assertThat(ComplexMetadataFailureKind.TRANSIENT.isSourceMissing()).isFalse();
		assertThat(ComplexMetadataFailureKind.TRANSIENT.isTransient()).isTrue();
		assertThat(ComplexMetadataFailureKind.PERMANENT.isTransient()).isFalse();
	}
}
