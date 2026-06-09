package com.home.application.ingest.normalization;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * normalized trade fallback identity 후보를 source_key registry에 연결할지 판단하는 application policy입니다.
 */
public class NormalizedTradeDuplicatePolicy {

	public NormalizedTradeDuplicateMatch resolve(
		String aptDong,
		Optional<Long> exactAptDongTradeId,
		Optional<Long> missingAptDongTradeId,
		List<Long> fallbackCandidateIds
	) {
		Optional<Long> exactCandidate = Objects.requireNonNullElse(exactAptDongTradeId, Optional.empty());
		Optional<Long> missingCandidate = Objects.requireNonNullElse(missingAptDongTradeId, Optional.empty());
		List<Long> fallbackCandidates = List.copyOf(Objects.requireNonNullElse(fallbackCandidateIds, List.of()));

		if (aptDong == null) {
			if (fallbackCandidates.size() == 1) {
				return NormalizedTradeDuplicateMatch.matched(fallbackCandidates.get(0));
			}
			return fallbackCandidates.isEmpty()
				? NormalizedTradeDuplicateMatch.none()
				: NormalizedTradeDuplicateMatch.ambiguousMatch();
		}

		if (exactCandidate.isPresent()) {
			return NormalizedTradeDuplicateMatch.matched(exactCandidate.get());
		}
		return missingCandidate
			.map(NormalizedTradeDuplicateMatch::matched)
			.orElseGet(NormalizedTradeDuplicateMatch::none);
	}
}
