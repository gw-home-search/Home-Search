package com.home.application.ingest.run;

import java.util.LinkedHashSet;
import java.util.List;

import com.home.domain.ingest.rtms.RtmsDealMonth;
import com.home.domain.ingest.run.RtmsIngestRunStatus;

/**
 * Read-only RTMS ingest run report criteria.
 */
public record RtmsIngestRunReportQuery(
	String lawdCd,
	String dealYmdFrom,
	String dealYmdTo,
	List<RtmsIngestRunStatus> statuses,
	int recentRunLimit
) {

	private static final int DEFAULT_RECENT_RUN_LIMIT = 10;
	private static final int MAX_RECENT_RUN_LIMIT = 100;

	public RtmsIngestRunReportQuery {
		lawdCd = trimToNull(lawdCd);
		dealYmdFrom = validateDealYmd(trimToNull(dealYmdFrom), "dealYmdFrom");
		dealYmdTo = validateDealYmd(trimToNull(dealYmdTo), "dealYmdTo");
		if (dealYmdFrom != null && dealYmdTo != null && dealYmdFrom.compareTo(dealYmdTo) > 0) {
			throw new IllegalArgumentException("dealYmdFrom must be before or equal to dealYmdTo");
		}
		statuses = normalizeStatuses(statuses);
		if (recentRunLimit < 1 || recentRunLimit > MAX_RECENT_RUN_LIMIT) {
			throw new IllegalArgumentException("recentRunLimit must be between 1 and 100");
		}
	}

	public static RtmsIngestRunReportQuery between(
		String lawdCd,
		String dealYmdFrom,
		String dealYmdTo
	) {
		return new RtmsIngestRunReportQuery(
			lawdCd,
			dealYmdFrom,
			dealYmdTo,
			RtmsIngestRunStatus.all(),
			DEFAULT_RECENT_RUN_LIMIT
		);
	}

	public List<String> statusNames() {
		return statuses.stream()
			.map(Enum::name)
			.toList();
	}

	private static List<RtmsIngestRunStatus> normalizeStatuses(List<RtmsIngestRunStatus> statuses) {
		if (statuses == null || statuses.isEmpty()) {
			return RtmsIngestRunStatus.all();
		}
		LinkedHashSet<RtmsIngestRunStatus> unique = new LinkedHashSet<>(statuses);
		if (unique.contains(null)) {
			throw new IllegalArgumentException("statuses must not contain null");
		}
		return List.copyOf(unique);
	}

	private static String validateDealYmd(String value, String fieldName) {
		return RtmsDealMonth.optional(value, fieldName + " must use yyyyMM format")
			.map(RtmsDealMonth::value)
			.orElse(null);
	}

	private static String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
