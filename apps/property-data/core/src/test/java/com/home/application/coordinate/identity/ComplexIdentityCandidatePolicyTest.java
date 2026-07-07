package com.home.application.coordinate.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.home.domain.coordinate.ComplexCoordinateIdentityVerificationStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplexIdentityCandidatePolicyTest {

	private final ComplexIdentityCandidatePolicy policy = new ComplexIdentityCandidatePolicy();

	@Test
	@DisplayName("identity candidate policy는 exact aptSeq와 parcel PNU가 일치하면 CONFIRMED를 반환한다")
	void confirmsExactAptSeqAndParcelPnuMatch() {
		ComplexCoordinateIdentityVerification verification = policy.verify(
			"11530-4350",
			"1153011200102380000",
			List.of(candidate("11530-4350", "1153011200102380000"))
		);

		assertThat(verification.status()).isEqualTo(ComplexCoordinateIdentityVerificationStatus.CONFIRMED);
		assertThat(verification.reason()).isEqualTo("ODC aptSeq/PNU identity confirmed");
	}

	@Test
	@DisplayName("identity candidate policy는 후보가 비어 있으면 UNAVAILABLE을 반환한다")
	void returnsUnavailableWhenCandidatesAreEmpty() {
		ComplexCoordinateIdentityVerification verification = policy.verify(
			"11530-4350",
			"1153011200102380000",
			List.of()
		);

		assertThat(verification.status()).isEqualTo(ComplexCoordinateIdentityVerificationStatus.UNAVAILABLE);
		assertThat(verification.reason()).isEqualTo("ODC identity candidate unavailable");
	}

	@Test
	@DisplayName("identity candidate policy는 exact aptSeq valid PNU가 없으면 UNAVAILABLE을 반환한다")
	void returnsUnavailableWhenExactCandidateHasNoValidPnu() {
		List<ComplexIdentityCandidate> candidates = List.of(
			candidate("11530-4351", "1153011200102380000"),
			candidate("11530-4350", "not-pnu"),
			candidate("11530-4350", " ")
		);

		ComplexCoordinateIdentityVerification verification = policy.verify(
			"11530-4350",
			"1153011200102380000",
			candidates
		);

		assertThat(verification.status()).isEqualTo(ComplexCoordinateIdentityVerificationStatus.UNAVAILABLE);
		assertThat(verification.reason()).isEqualTo("ODC exact COMPLEX_PK candidate unavailable");
	}

	@Test
	@DisplayName("identity candidate policy는 exact aptSeq valid PNU가 다중이면 AMBIGUOUS를 반환한다")
	void returnsAmbiguousWhenExactCandidateHasMultipleDistinctPnus() {
		List<ComplexIdentityCandidate> candidates = List.of(
			candidate("11530-4350", "1153011200102380000"),
			candidate("11530-4350", "1153011200102390000")
		);

		ComplexCoordinateIdentityVerification verification = policy.verify(
			"11530-4350",
			"1153011200102380000",
			candidates
		);

		assertThat(verification.status()).isEqualTo(ComplexCoordinateIdentityVerificationStatus.AMBIGUOUS);
		assertThat(verification.reason()).isEqualTo("ODC exact COMPLEX_PK has multiple PNU candidates");
	}

	@Test
	@DisplayName("identity candidate policy는 단일 ODC PNU가 parcel PNU와 다르면 AMBIGUOUS를 반환한다")
	void returnsAmbiguousWhenOdcloudPnuConflictsWithParcelPnu() {
		ComplexCoordinateIdentityVerification verification = policy.verify(
			"11530-4350",
			"1153011200102380000",
			List.of(candidate("11530-4350", "1153011200102390000"))
		);

		assertThat(verification.status()).isEqualTo(ComplexCoordinateIdentityVerificationStatus.AMBIGUOUS);
		assertThat(verification.reason()).isEqualTo("ODC PNU conflicts with parcel PNU");
	}

	@Test
	@DisplayName("identity candidate policy는 resolver 경로에서 exact aptSeq 단일 valid PNU만 반환한다")
	void resolvesOnlySingleExactValidPnu() {
		assertThat(policy.resolveUniquePnu(
			"11530-4350",
			List.of(candidate("11530-4350", "1153011200102380000"))
		)).contains("1153011200102380000");
		assertThat(policy.resolveUniquePnu("11530-4350", List.of())).isEmpty();
		assertThat(policy.resolveUniquePnu(
			"11530-4350",
			List.of(
				candidate("11530-4350", "1153011200102380000"),
				candidate("11530-4350", "1153011200102390000")
			)
		)).isEmpty();
		assertThat(policy.resolveUniquePnu(
			"11530-4350",
			List.of(candidate("11530-4350", "invalid"))
		)).isEmpty();
	}

	private ComplexIdentityCandidate candidate(String complexPk, String pnu) {
		return new ComplexIdentityCandidate(complexPk, pnu);
	}
}
