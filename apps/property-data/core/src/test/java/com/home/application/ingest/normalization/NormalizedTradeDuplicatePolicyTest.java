package com.home.application.ingest.normalization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NormalizedTradeDuplicatePolicyTest {

	private final NormalizedTradeDuplicatePolicy policy = new NormalizedTradeDuplicatePolicy();

	@Test
	@DisplayName("aptDong이 있으면 exact aptDong 후보를 duplicate로 선택한다")
	void matchesExactAptDongCandidate() {
		NormalizedTradeDuplicateMatch match = policy.resolve("101", Optional.of(11L), Optional.of(22L), List.of());

		assertThat(match.tradeId()).contains(11L);
		assertThat(match.ambiguous()).isFalse();
	}

	@Test
	@DisplayName("aptDong이 있으면 exact 후보가 없을 때 missing aptDong 후보를 duplicate로 선택한다")
	void matchesMissingAptDongCandidateForFilledAptDongCommand() {
		NormalizedTradeDuplicateMatch match = policy.resolve("101", Optional.empty(), Optional.of(22L), List.of());

		assertThat(match.tradeId()).contains(22L);
		assertThat(match.ambiguous()).isFalse();
	}

	@Test
	@DisplayName("aptDong이 있고 기존 후보가 없으면 새 normalized trade insert를 허용한다")
	void allowsInsertForNewFilledAptDongCommand() {
		NormalizedTradeDuplicateMatch match = policy.resolve("101", Optional.empty(), Optional.empty(), List.of());

		assertThat(match.tradeId()).isEmpty();
		assertThat(match.ambiguous()).isFalse();
	}

	@Test
	@DisplayName("aptDong이 없으면 fallback identity 단일 후보를 duplicate로 선택한다")
	void matchesSingleFallbackCandidateForMissingAptDongCommand() {
		NormalizedTradeDuplicateMatch match = policy.resolve(null, Optional.empty(), Optional.empty(), List.of(31L));

		assertThat(match.tradeId()).contains(31L);
		assertThat(match.ambiguous()).isFalse();
	}

	@Test
	@DisplayName("aptDong이 없고 fallback identity 후보가 없으면 새 normalized trade insert를 허용한다")
	void allowsInsertWhenMissingAptDongHasNoFallbackCandidate() {
		NormalizedTradeDuplicateMatch match = policy.resolve(null, Optional.empty(), Optional.empty(), List.of());

		assertThat(match.tradeId()).isEmpty();
		assertThat(match.ambiguous()).isFalse();
	}

	@Test
	@DisplayName("aptDong이 없고 fallback identity 후보가 복수이면 임의 연결하지 않는다")
	void rejectsAmbiguousFallbackCandidatesForMissingAptDongCommand() {
		NormalizedTradeDuplicateMatch match = policy.resolve(null, Optional.empty(), Optional.empty(), List.of(31L, 32L));

		assertThat(match.tradeId()).isEmpty();
		assertThat(match.ambiguous()).isTrue();
	}
}
