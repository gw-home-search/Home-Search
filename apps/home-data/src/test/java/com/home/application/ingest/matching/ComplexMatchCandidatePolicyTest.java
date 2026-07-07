package com.home.application.ingest.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.home.domain.ingest.matching.TradeMatchStatus;
import com.home.domain.trade.RtmsJibunPnu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplexMatchCandidatePolicyTest {

	private final ComplexMatchCandidatePolicy policy = new ComplexMatchCandidatePolicy();

	@Test
	@DisplayName("aptSeq 단일 후보는 derived PNU가 같을 때 APTSEQ로 확정한다")
	void confirmsUniqueAptSeqCandidateWhenPnuMatches() {
		ComplexMatchResult result = policy.match(
			"APT-501",
			"Sample Apartment",
			availablePnu("1168010300101400001"),
			List.of(candidate(501L, "COMPLEX-PK-501", "Sample Apartment", "Sample Apartment", "1168010300101400001")),
			List.of()
		);

		assertThat(result.matched()).isTrue();
		assertThat(result.complexId()).isEqualTo(501L);
		assertThat(result.complexPk()).isEqualTo("COMPLEX-PK-501");
		assertThat(result.matchPath()).isEqualTo("APTSEQ");
		assertThat(result.matchStatus()).isEqualTo(TradeMatchStatus.MATCHED);
		assertThat(result.candidateCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("aptSeq 단일 후보와 derived PNU가 다르면 PNU_CONFLICT로 보류한다")
	void rejectsUniqueAptSeqCandidateWhenPnuConflicts() {
		ComplexMatchResult result = policy.match(
			"APT-501",
			"Sample Apartment",
			availablePnu("1168010300109990001"),
			List.of(candidate(501L, "COMPLEX-PK-501", "Sample Apartment", "Sample Apartment", "1168010300101400001")),
			List.of(candidate(502L, "COMPLEX-PK-502", "Other Apartment", "Other Apartment", "1168010300109990001"))
		);

		assertThat(result.matched()).isFalse();
		assertThat(result.matchStatus()).isEqualTo(TradeMatchStatus.PNU_CONFLICT);
		assertThat(result.candidateCount()).isEqualTo(2);
		assertThat(result.candidateComplexIds()).containsExactly(501L, 502L);
		assertThat(result.failureReason()).contains("APTSEQ parcel pnu conflict");
	}

	@Test
	@DisplayName("aptSeq 후보가 다중이면 PNU 후보를 임의 선택하지 않고 AMBIGUOUS로 보류한다")
	void rejectsMultipleAptSeqCandidates() {
		ComplexMatchResult result = policy.match(
			"APT-501",
			"Sample Apartment",
			availablePnu("1168010300101400001"),
			List.of(
				candidate(501L, "COMPLEX-PK-501", "Sample Apartment", "Sample Apartment", "1168010300101400001"),
				candidate(502L, "COMPLEX-PK-502", "Sample Apartment", "Sample Apartment", "1168010300101400001")
			),
			List.of()
		);

		assertThat(result.matched()).isFalse();
		assertThat(result.matchStatus()).isEqualTo(TradeMatchStatus.AMBIGUOUS);
		assertThat(result.candidateCount()).isEqualTo(2);
		assertThat(result.candidateComplexIds()).containsExactly(501L, 502L);
	}

	@Test
	@DisplayName("PNU를 만들 수 없으면 PNU_UNAVAILABLE evidence를 반환한다")
	void returnsUnavailableWhenPnuCannotBeDerived() {
		ComplexMatchResult result = policy.match(
			null,
			"Sample Apartment",
			unavailablePnu("invalid jibun"),
			List.of(),
			List.of()
		);

		assertThat(result.matched()).isFalse();
		assertThat(result.matchStatus()).isEqualTo(TradeMatchStatus.PNU_UNAVAILABLE);
		assertThat(result.failureReason()).contains("invalid jibun");
	}

	@Test
	@DisplayName("PNU 후보가 없으면 UNMATCHED evidence를 반환한다")
	void returnsUnmatchedWhenPnuHasNoCandidate() {
		ComplexMatchResult result = policy.match(
			"APT-404",
			"Missing Apartment",
			availablePnu("1168010300109990001"),
			List.of(),
			List.of()
		);

		assertThat(result.matched()).isFalse();
		assertThat(result.matchStatus()).isEqualTo(TradeMatchStatus.UNMATCHED);
		assertThat(result.failureReason()).contains("APT-404");
		assertThat(result.failureReason()).contains("1168010300109990001");
	}

	@Test
	@DisplayName("PNU 단일 후보라도 RTMS 이름 근거가 없으면 NAME_CONFLICT로 보류한다")
	void rejectsSinglePnuCandidateWhenNameDoesNotMatch() {
		ComplexMatchResult result = policy.match(
			null,
			"Different Apartment",
			availablePnu("1168010300101400001"),
			List.of(),
			List.of(candidate(501L, "COMPLEX-PK-501", "Sample Apartment", "Sample Apartment", "1168010300101400001"))
		);

		assertThat(result.matched()).isFalse();
		assertThat(result.matchStatus()).isEqualTo(TradeMatchStatus.NAME_CONFLICT);
		assertThat(result.candidateComplexIds()).containsExactly(501L);
	}

	@Test
	@DisplayName("PNU 다중 후보는 master name과 alias score로 단일 후보를 선택한다")
	void choosesMultiplePnuCandidateByNameAndAliasScore() {
		ComplexMatchResult result = policy.match(
			null,
			"Official Trade Name",
			availablePnu("1168010300101400001"),
			List.of(),
			List.of(
				candidate(501L, "COMPLEX-PK-501", "Sample Apartment", "Sample Apartment", "1168010300101400001",
					List.of("officialtradename")),
				candidate(502L, "COMPLEX-PK-502", "Building Register Name", "Official Trade Name",
					"1168010300101400001")
			)
		);

		assertThat(result.matched()).isTrue();
		assertThat(result.complexId()).isEqualTo(502L);
		assertThat(result.matchPath()).isEqualTo("PNU_NAME");
		assertThat(result.matchStatus()).isEqualTo(TradeMatchStatus.MATCHED);
	}

	@Test
	@DisplayName("PNU 다중 후보의 name score가 동점이면 AMBIGUOUS로 보류한다")
	void rejectsMultiplePnuCandidatesWhenNameScoreTies() {
		ComplexMatchResult result = policy.match(
			null,
			"Shared Name",
			availablePnu("1168010300101400001"),
			List.of(),
			List.of(
				candidate(501L, "COMPLEX-PK-501", "Shared Name", "Sample Apartment", "1168010300101400001"),
				candidate(502L, "COMPLEX-PK-502", "Shared Name", "Other Apartment", "1168010300101400001")
			)
		);

		assertThat(result.matched()).isFalse();
		assertThat(result.matchStatus()).isEqualTo(TradeMatchStatus.AMBIGUOUS);
		assertThat(result.candidateCount()).isEqualTo(2);
	}

	private ComplexMatchCandidate candidate(
		Long complexId,
		String complexPk,
		String tradeName,
		String name,
		String parcelPnu
	) {
		return candidate(complexId, complexPk, tradeName, name, parcelPnu, List.of());
	}

	private ComplexMatchCandidate candidate(
		Long complexId,
		String complexPk,
		String tradeName,
		String name,
		String parcelPnu,
		List<String> normalizedAliases
	) {
		return new ComplexMatchCandidate(complexId, complexPk, tradeName, name, parcelPnu, normalizedAliases);
	}

	private RtmsJibunPnu availablePnu(String pnu) {
		return new RtmsJibunPnu("140-1", "140-1", pnu.substring(0, 5), pnu.substring(5, 10), pnu.substring(10, 11),
			pnu.substring(11, 15), pnu.substring(15, 19), pnu, null);
	}

	private RtmsJibunPnu unavailablePnu(String reason) {
		return RtmsJibunPnu.unavailable("번지없음", "번지없음", "11680", "10300", reason);
	}
}
