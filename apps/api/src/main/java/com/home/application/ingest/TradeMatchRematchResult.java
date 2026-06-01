package com.home.application.ingest;

public record TradeMatchRematchResult(
	int processed,
	int normalized,
	int duplicate,
	int stillFailed,
	int parseFailed,
	int skipped
) {

	public static TradeMatchRematchResult empty() {
		return new TradeMatchRematchResult(0, 0, 0, 0, 0, 0);
	}

	public TradeMatchRematchResult plusNormalized() {
		return new TradeMatchRematchResult(processed + 1, normalized + 1, duplicate, stillFailed, parseFailed, skipped);
	}

	public TradeMatchRematchResult plusDuplicate() {
		return new TradeMatchRematchResult(processed + 1, normalized, duplicate + 1, stillFailed, parseFailed, skipped);
	}

	public TradeMatchRematchResult plusStillFailed() {
		return new TradeMatchRematchResult(processed + 1, normalized, duplicate, stillFailed + 1, parseFailed, skipped);
	}

	public TradeMatchRematchResult plusParseFailed() {
		return new TradeMatchRematchResult(processed + 1, normalized, duplicate, stillFailed, parseFailed + 1, skipped);
	}

	public TradeMatchRematchResult plusSkipped() {
		return new TradeMatchRematchResult(processed + 1, normalized, duplicate, stillFailed, parseFailed, skipped + 1);
	}
}
