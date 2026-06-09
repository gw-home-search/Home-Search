package com.home.domain.coordinate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.home.domain.coordinate.ComplexCoordinateIdentityVerificationStatus;

class ComplexCoordinateCaseStatusTest {

	@Test
	@DisplayName("coordinate case status는 unresolved fallback confidence 대상만 구분한다")
	void unresolvedStatusesUseFallbackConfidence() {
		assertThat(ComplexCoordinateCaseStatus.AMBIGUOUS.usesUnresolvedFallbackConfidence()).isTrue();
		assertThat(ComplexCoordinateCaseStatus.UNAVAILABLE.usesUnresolvedFallbackConfidence()).isTrue();
		assertThat(ComplexCoordinateCaseStatus.FAILED.usesUnresolvedFallbackConfidence()).isTrue();
		assertThat(ComplexCoordinateCaseStatus.PENDING.usesUnresolvedFallbackConfidence()).isFalse();
		assertThat(ComplexCoordinateCaseStatus.RESOLVED.usesUnresolvedFallbackConfidence()).isFalse();
		assertThat(ComplexCoordinateCaseStatus.SKIPPED.usesUnresolvedFallbackConfidence()).isFalse();
	}

	@Test
	@DisplayName("identity verification status는 차단 정책과 case status 변환을 직접 담당한다")
	void identityVerificationStatusOwnsBlockingDecision() {
		assertThat(ComplexCoordinateIdentityVerificationStatus.CONFIRMED.shouldBlock(true, true)).isFalse();
		assertThat(ComplexCoordinateIdentityVerificationStatus.AMBIGUOUS.shouldBlock(false, false)).isTrue();
		assertThat(ComplexCoordinateIdentityVerificationStatus.UNAVAILABLE.shouldBlock(true, false)).isTrue();
		assertThat(ComplexCoordinateIdentityVerificationStatus.UNAVAILABLE.shouldBlock(false, true)).isFalse();
		assertThat(ComplexCoordinateIdentityVerificationStatus.FAILED.shouldBlock(false, true)).isTrue();
		assertThat(ComplexCoordinateIdentityVerificationStatus.FAILED.shouldBlock(true, false)).isFalse();

		assertThat(ComplexCoordinateIdentityVerificationStatus.AMBIGUOUS.toBlockedCaseStatus())
			.isEqualTo(ComplexCoordinateCaseStatus.AMBIGUOUS);
		assertThat(ComplexCoordinateIdentityVerificationStatus.UNAVAILABLE.toBlockedCaseStatus())
			.isEqualTo(ComplexCoordinateCaseStatus.UNAVAILABLE);
		assertThat(ComplexCoordinateIdentityVerificationStatus.FAILED.toBlockedCaseStatus())
			.isEqualTo(ComplexCoordinateCaseStatus.FAILED);
		assertThatThrownBy(ComplexCoordinateIdentityVerificationStatus.CONFIRMED::toBlockedCaseStatus)
			.isInstanceOf(IllegalStateException.class);
	}
}
