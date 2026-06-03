package com.home.infrastructure.external.rtms;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

record RtmsOneShotIngestProperties(
	boolean enabled,
	String lawdCd,
	String dealYmd,
	Integer pageNo,
	boolean preflightOnly,
	String mode,
	Integer lookbackMonths,
	boolean allowCoordinatePendingOnly,
	String nationwideLawdCds,
	String nationwideDealYmdFrom,
	String nationwideDealYmdTo,
	String nationwideJobKey,
	String nationwideWorkerId,
	Integer nationwideLeaseMinutes,
	Integer nationwideMaxAttemptCount,
	Integer nationwideChunkLimit
) {

	RtmsOneShotIngestProperties(
		boolean enabled,
		String lawdCd,
		String dealYmd,
		Integer pageNo,
		boolean preflightOnly
	) {
		this(enabled, lawdCd, dealYmd, pageNo, preflightOnly, "one-shot", 0, false);
	}

	RtmsOneShotIngestProperties(
		boolean enabled,
		String lawdCd,
		String dealYmd,
		Integer pageNo,
		boolean preflightOnly,
		String mode,
		Integer lookbackMonths
	) {
		this(enabled, lawdCd, dealYmd, pageNo, preflightOnly, mode, lookbackMonths, false);
	}

	RtmsOneShotIngestProperties(
		boolean enabled,
		String lawdCd,
		String dealYmd,
		Integer pageNo,
		boolean preflightOnly,
		String mode,
		Integer lookbackMonths,
		boolean allowCoordinatePendingOnly
	) {
		this(
			enabled,
			lawdCd,
			dealYmd,
			pageNo,
			preflightOnly,
			mode,
			lookbackMonths,
			allowCoordinatePendingOnly,
			"",
			"201201",
			"202606",
			"",
			"rtms-backfill-worker",
			30,
			3,
			Integer.MAX_VALUE
		);
	}

	RtmsApartmentTradeRequest request() {
		return new RtmsApartmentTradeRequest(lawdCd, dealYmd, pageNo);
	}

	RtmsIngestMode ingestMode() {
		return RtmsIngestMode.from(mode);
	}

	RtmsMonthlyRefreshPlan monthlyRefreshPlan() {
		return new RtmsMonthlyRefreshPlan(lawdCd, dealYmd, lookbackMonths == null ? 0 : lookbackMonths);
	}

	RtmsNationwideBackfillPlan nationwideBackfillPlan() {
		String from = blankToDefault(nationwideDealYmdFrom, "201201");
		String to = blankToDefault(nationwideDealYmdTo, "202606");
		String jobKey = blankToDefault(nationwideJobKey, "rtms-national-" + from + "-" + to);
		return new RtmsNationwideBackfillPlan(jobKey, nationwideLawdCdList(), from, to);
	}

	RtmsNationwideBackfillOptions nationwideBackfillOptions() {
		return new RtmsNationwideBackfillOptions(
			blankToDefault(nationwideWorkerId, "rtms-backfill-worker"),
			Duration.ofMinutes(nationwideLeaseMinutes == null ? 30 : nationwideLeaseMinutes),
			nationwideMaxAttemptCount == null ? 3 : nationwideMaxAttemptCount,
			nationwideChunkLimit == null ? Integer.MAX_VALUE : nationwideChunkLimit
		);
	}

	private List<String> nationwideLawdCdList() {
		if (nationwideLawdCds == null || nationwideLawdCds.isBlank()) {
			throw new IllegalArgumentException("home.ingest.rtms.nationwide.lawd-cds is required");
		}
		return Arrays.stream(nationwideLawdCds.split(","))
			.map(String::trim)
			.filter(value -> !value.isBlank())
			.toList();
	}

	private String blankToDefault(String value, String defaultValue) {
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		return value.trim();
	}
}
