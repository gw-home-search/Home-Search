package com.home.application.ingest.matching;

import java.util.Optional;

/**
 * RTMS master bootstrap의 identity 검증과 skip reason 생성을 소유하는 application policy입니다.
 */
public class ComplexMasterBootstrapPolicy {

	private static final int COMPLEX_PK_MAX_LENGTH = 64;

	public Optional<ComplexMasterBootstrapResult> validateAptSeq(String aptSeq) {
		if (trimToNull(aptSeq) == null) {
			return Optional.of(ComplexMasterBootstrapResult.skipped("master bootstrap skipped: aptSeq unavailable"));
		}
		return Optional.empty();
	}

	public Optional<ComplexMasterBootstrapResult> validateExistingAptSeqCandidateCount(
		String aptSeq,
		int existingComplexCount
	) {
		if (existingComplexCount > 1) {
			return Optional.of(ComplexMasterBootstrapResult.skipped(
				"master bootstrap skipped: ambiguous aptSeq=" + aptSeq
			));
		}
		return Optional.empty();
	}

	public Optional<ComplexMasterBootstrapResult> validateExistingAptSeqPnu(
		String aptSeq,
		Optional<String> pnu,
		Optional<String> complexPnu
	) {
		if (pnu.isEmpty()) {
			return Optional.of(ComplexMasterBootstrapResult.skipped(
				"master bootstrap skipped: pnu unavailable aptSeq=" + aptSeq
			));
		}
		if (complexPnu.isEmpty()) {
			return Optional.of(ComplexMasterBootstrapResult.skipped(
				"master bootstrap skipped: complex parcel unavailable aptSeq=" + aptSeq
			));
		}
		if (!pnu.get().equals(complexPnu.get())) {
			return Optional.of(ComplexMasterBootstrapResult.skipped(
				"master bootstrap skipped: aptSeq parcel pnu conflict aptSeq=%s derivedPnu=%s complexPnu=%s"
					.formatted(aptSeq, pnu.get(), complexPnu.get())
			));
		}
		return Optional.empty();
	}

	public Optional<ComplexMasterBootstrapResult> validateNewInput(
		String aptSeq,
		String aptName,
		Optional<String> pnu
	) {
		Optional<ComplexMasterBootstrapResult> aptNameResult = validateNewAptName(aptSeq, aptName);
		if (aptNameResult.isPresent()) {
			return aptNameResult;
		}
		return validateNewPnu(aptSeq, pnu);
	}

	public Optional<ComplexMasterBootstrapResult> validateNewAptName(String aptSeq, String aptName) {
		if (trimToNull(aptName) == null) {
			return Optional.of(ComplexMasterBootstrapResult.skipped(
				"master bootstrap skipped: aptName unavailable aptSeq=" + aptSeq
			));
		}
		return Optional.empty();
	}

	public Optional<ComplexMasterBootstrapResult> validateNewPnu(String aptSeq, Optional<String> pnu) {
		if (pnu.isEmpty()) {
			return Optional.of(ComplexMasterBootstrapResult.skipped(
				"master bootstrap skipped: pnu unavailable aptSeq=" + aptSeq
			));
		}
		return Optional.empty();
	}

	public String complexPk(String aptSeq) {
		return "RTMS:" + aptSeq;
	}

	public Optional<ComplexMasterBootstrapResult> validateExistingComplexPkPnu(
		String complexPk,
		String pnu,
		Optional<String> complexPnu
	) {
		if (complexPnu.isEmpty()) {
			return Optional.of(ComplexMasterBootstrapResult.skipped(
				"master bootstrap skipped: complex parcel unavailable complexPk=" + complexPk
			));
		}
		if (!pnu.equals(complexPnu.get())) {
			return Optional.of(ComplexMasterBootstrapResult.skipped(
				"master bootstrap skipped: complex_pk parcel pnu conflict complexPk=%s derivedPnu=%s complexPnu=%s"
					.formatted(complexPk, pnu, complexPnu.get())
			));
		}
		return Optional.empty();
	}

	public Optional<ComplexMasterBootstrapResult> validateParcel(String pnu, Optional<Long> parcelId) {
		if (parcelId.isEmpty()) {
			return Optional.of(ComplexMasterBootstrapResult.skipped(
				"master bootstrap skipped: parcel unavailable pnu=" + pnu
			));
		}
		return Optional.empty();
	}

	public Optional<ComplexMasterBootstrapResult> validateComplexPkLength(String aptSeq, String complexPk) {
		if (complexPk.length() > COMPLEX_PK_MAX_LENGTH) {
			return Optional.of(ComplexMasterBootstrapResult.skipped(
				"master bootstrap skipped: complex_pk too long aptSeq=" + aptSeq
			));
		}
		return Optional.empty();
	}

	private String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
