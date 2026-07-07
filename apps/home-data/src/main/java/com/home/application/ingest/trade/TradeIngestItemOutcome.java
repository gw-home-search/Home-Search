package com.home.application.ingest.trade;

/**
 * RTMS item 하나를 ingest한 결과를 batch count로 누적하기 위한 application 결과입니다.
 */
public record TradeIngestItemOutcome(
	long normalizedInsertedCount,
	long duplicateSkippedCount,
	long canceledSkippedCount,
	long matchFailedCount,
	long parseFailedCount
) {

	public static TradeIngestItemOutcome normalized() {
		return new TradeIngestItemOutcome(1, 0, 0, 0, 0);
	}

	public static TradeIngestItemOutcome duplicate() {
		return new TradeIngestItemOutcome(0, 1, 0, 0, 0);
	}

	public static TradeIngestItemOutcome canceled() {
		return new TradeIngestItemOutcome(0, 0, 1, 0, 0);
	}

	public static TradeIngestItemOutcome matchFailed() {
		return new TradeIngestItemOutcome(0, 0, 0, 1, 0);
	}

	public static TradeIngestItemOutcome parseFailed() {
		return new TradeIngestItemOutcome(0, 0, 0, 0, 1);
	}

	public static Accumulator accumulator() {
		return new Accumulator();
	}

	public static final class Accumulator {
		private long normalizedInserted;
		private long duplicateSkipped;
		private long canceledSkipped;
		private long matchFailed;
		private long parseFailed;

		public void add(TradeIngestItemOutcome outcome) {
			if (outcome == null) {
				return;
			}
			normalizedInserted += outcome.normalizedInsertedCount();
			duplicateSkipped += outcome.duplicateSkippedCount();
			canceledSkipped += outcome.canceledSkippedCount();
			matchFailed += outcome.matchFailedCount();
			parseFailed += outcome.parseFailedCount();
		}

		public IngestResult toResult(long read, long rawSaved) {
			return new IngestResult(
				read,
				rawSaved,
				normalizedInserted,
				duplicateSkipped,
				canceledSkipped,
				matchFailed,
				parseFailed
			);
		}
	}
}
