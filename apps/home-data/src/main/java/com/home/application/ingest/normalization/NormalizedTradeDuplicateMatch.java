package com.home.application.ingest.normalization;

import java.util.Optional;

public record NormalizedTradeDuplicateMatch(
	Optional<Long> tradeId,
	boolean ambiguous
) {

	public NormalizedTradeDuplicateMatch {
		tradeId = tradeId == null ? Optional.empty() : tradeId;
	}

	public static NormalizedTradeDuplicateMatch matched(Long tradeId) {
		return new NormalizedTradeDuplicateMatch(Optional.of(tradeId), false);
	}

	public static NormalizedTradeDuplicateMatch none() {
		return new NormalizedTradeDuplicateMatch(Optional.empty(), false);
	}

	public static NormalizedTradeDuplicateMatch ambiguousMatch() {
		return new NormalizedTradeDuplicateMatch(Optional.empty(), true);
	}
}
