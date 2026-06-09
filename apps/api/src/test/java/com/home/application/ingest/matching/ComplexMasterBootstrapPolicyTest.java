package com.home.application.ingest.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplexMasterBootstrapPolicyTest {

	private final ComplexMasterBootstrapPolicy policy = new ComplexMasterBootstrapPolicy();

	@Test
	@DisplayName("aptSeq가 없으면 bootstrap을 skip한다")
	void skipsWhenAptSeqIsMissing() {
		Optional<ComplexMasterBootstrapResult> result = policy.validateAptSeq(" ");

		assertThat(result).isPresent();
		assertThat(result.get().failureReason()).isEqualTo("master bootstrap skipped: aptSeq unavailable");
	}

	@Test
	@DisplayName("existing aptSeq 후보가 다중이면 ambiguous aptSeq로 skip한다")
	void skipsWhenExistingAptSeqCandidatesAreAmbiguous() {
		Optional<ComplexMasterBootstrapResult> result = policy.validateExistingAptSeqCandidateCount("APT-501", 2);

		assertThat(result).isPresent();
		assertThat(result.get().failureReason()).isEqualTo("master bootstrap skipped: ambiguous aptSeq=APT-501");
	}

	@Test
	@DisplayName("existing aptSeq 후보가 PNU를 만들 수 없으면 skip한다")
	void skipsExistingAptSeqWhenPnuIsUnavailable() {
		Optional<ComplexMasterBootstrapResult> result = policy.validateExistingAptSeqPnu(
			"APT-501",
			Optional.empty(),
			Optional.of("1168010300101400001")
		);

		assertThat(result).isPresent();
		assertThat(result.get().failureReason()).isEqualTo("master bootstrap skipped: pnu unavailable aptSeq=APT-501");
	}

	@Test
	@DisplayName("existing aptSeq 후보의 parcel PNU가 없으면 skip한다")
	void skipsExistingAptSeqWhenComplexParcelIsUnavailable() {
		Optional<ComplexMasterBootstrapResult> result = policy.validateExistingAptSeqPnu(
			"APT-501",
			Optional.of("1168010300101400001"),
			Optional.empty()
		);

		assertThat(result).isPresent();
		assertThat(result.get().failureReason())
			.isEqualTo("master bootstrap skipped: complex parcel unavailable aptSeq=APT-501");
	}

	@Test
	@DisplayName("existing aptSeq 후보와 derived PNU가 충돌하면 skip한다")
	void skipsExistingAptSeqWhenPnuConflicts() {
		Optional<ComplexMasterBootstrapResult> result = policy.validateExistingAptSeqPnu(
			"APT-501",
			Optional.of("1168010300107770001"),
			Optional.of("1168010300101400001")
		);

		assertThat(result).isPresent();
		assertThat(result.get().failureReason())
			.isEqualTo("master bootstrap skipped: aptSeq parcel pnu conflict aptSeq=APT-501 derivedPnu=1168010300107770001 complexPnu=1168010300101400001");
	}

	@Test
	@DisplayName("신규 bootstrap input은 aptName과 PNU가 모두 있어야 한다")
	void validatesNewBootstrapInput() {
		assertThat(policy.validateNewInput("APT-501", null, Optional.of("1168010300107770001")))
			.get()
			.extracting(ComplexMasterBootstrapResult::failureReason)
			.isEqualTo("master bootstrap skipped: aptName unavailable aptSeq=APT-501");
		assertThat(policy.validateNewInput("APT-501", "Sample Apartment", Optional.empty()))
			.get()
			.extracting(ComplexMasterBootstrapResult::failureReason)
			.isEqualTo("master bootstrap skipped: pnu unavailable aptSeq=APT-501");
		assertThat(policy.validateNewInput("APT-501", "Sample Apartment", Optional.of("1168010300107770001")))
			.isEmpty();
	}

	@Test
	@DisplayName("complexPk는 RTMS prefix를 붙이고 64자를 넘으면 skip한다")
	void buildsAndValidatesComplexPk() {
		String complexPk = policy.complexPk("APT-501");

		assertThat(complexPk).isEqualTo("RTMS:APT-501");
		assertThat(policy.validateComplexPkLength("APT-501", complexPk)).isEmpty();
		assertThat(policy.validateComplexPkLength("A".repeat(70), "RTMS:" + "A".repeat(70)))
			.get()
			.extracting(ComplexMasterBootstrapResult::failureReason)
			.isEqualTo("master bootstrap skipped: complex_pk too long aptSeq=" + "A".repeat(70));
	}

	@Test
	@DisplayName("existing complexPk 후보의 parcel PNU가 없거나 충돌하면 skip한다")
	void validatesExistingComplexPkPnu() {
		assertThat(policy.validateExistingComplexPkPnu("RTMS:APT-501", "1168010300107770001", Optional.empty()))
			.get()
			.extracting(ComplexMasterBootstrapResult::failureReason)
			.isEqualTo("master bootstrap skipped: complex parcel unavailable complexPk=RTMS:APT-501");
		assertThat(policy.validateExistingComplexPkPnu(
			"RTMS:APT-501",
			"1168010300107770001",
			Optional.of("1168010300101400001")
		))
			.get()
			.extracting(ComplexMasterBootstrapResult::failureReason)
			.isEqualTo("master bootstrap skipped: complex_pk parcel pnu conflict complexPk=RTMS:APT-501 derivedPnu=1168010300107770001 complexPnu=1168010300101400001");
	}

	@Test
	@DisplayName("parcel을 만들거나 찾을 수 없으면 skip한다")
	void validatesParcelAvailability() {
		Optional<ComplexMasterBootstrapResult> result = policy.validateParcel(
			"1168010300107770001",
			Optional.empty()
		);

		assertThat(result).isPresent();
		assertThat(result.get().failureReason())
			.isEqualTo("master bootstrap skipped: parcel unavailable pnu=1168010300107770001");
	}
}
