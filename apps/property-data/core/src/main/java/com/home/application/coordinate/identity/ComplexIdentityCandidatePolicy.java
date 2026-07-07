package com.home.application.coordinate.identity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ODC 후보를 프로젝트 complex identity 판정으로 변환하는 application 정책이다.
 */
public class ComplexIdentityCandidatePolicy {

	public ComplexCoordinateIdentityVerification verify(
		String aptSeq,
		String parcelPnu,
		List<ComplexIdentityCandidate> candidates
	) {
		if (candidates == null || candidates.isEmpty()) {
			return ComplexCoordinateIdentityVerification.unavailable("ODC identity candidate unavailable");
		}
		List<String> pnus = exactValidPnus(aptSeq, candidates);
		if (pnus.isEmpty()) {
			return ComplexCoordinateIdentityVerification.unavailable("ODC exact COMPLEX_PK candidate unavailable");
		}
		if (pnus.size() > 1) {
			return ComplexCoordinateIdentityVerification.ambiguous("ODC exact COMPLEX_PK has multiple PNU candidates");
		}
		String normalizedParcelPnu = trimToNull(parcelPnu);
		if (!pnus.get(0).equals(normalizedParcelPnu)) {
			return ComplexCoordinateIdentityVerification.ambiguous("ODC PNU conflicts with parcel PNU");
		}
		return ComplexCoordinateIdentityVerification.confirmed("ODC aptSeq/PNU identity confirmed");
	}

	public Optional<String> resolveUniquePnu(String aptSeq, List<ComplexIdentityCandidate> candidates) {
		List<String> pnus = exactValidPnus(aptSeq, candidates);
		return pnus.size() == 1 ? Optional.of(pnus.get(0)) : Optional.empty();
	}

	private List<String> exactValidPnus(String aptSeq, List<ComplexIdentityCandidate> candidates) {
		String normalizedAptSeq = trimToNull(aptSeq);
		if (normalizedAptSeq == null || candidates == null || candidates.isEmpty()) {
			return List.of();
		}
		return candidates.stream()
			.filter(Objects::nonNull)
			.filter(candidate -> normalizedAptSeq.equals(trimToNull(candidate.complexPk())))
			.map(ComplexIdentityCandidate::pnu)
			.map(this::trimToNull)
			.filter(this::validPnu)
			.distinct()
			.toList();
	}

	private boolean validPnu(String value) {
		return value != null && value.matches("\\d{19}");
	}

	private String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
