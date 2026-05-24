package com.home.application.ingest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * read-only raw ingest failure inspection 조건입니다.
 */
public record RawTradeIngestFailureQuery(
	String source,
	String lawdCd,
	String dealYmdFrom,
	String dealYmdTo,
	List<RawTradeIngestStatus> statuses
) {

	private static final Pattern DEAL_YMD = Pattern.compile("\\d{6}");
	private static final List<RawTradeIngestStatus> DEFAULT_FAILURE_STATUSES = List.of(
		RawTradeIngestStatus.MATCH_FAILED,
		RawTradeIngestStatus.PARSE_FAILED,
		RawTradeIngestStatus.DUPLICATE
	);
	private static final Set<RawTradeIngestStatus> ALLOWED_STATUSES = Set.copyOf(DEFAULT_FAILURE_STATUSES);

	public RawTradeIngestFailureQuery {
		source = trimToNull(source);
		lawdCd = trimToNull(lawdCd);
		dealYmdFrom = validateDealYmd(trimToNull(dealYmdFrom), "dealYmdFrom");
		dealYmdTo = validateDealYmd(trimToNull(dealYmdTo), "dealYmdTo");
		if (dealYmdFrom != null && dealYmdTo != null && dealYmdFrom.compareTo(dealYmdTo) > 0) {
			throw new IllegalArgumentException("dealYmdFrom must be before or equal to dealYmdTo");
		}
		statuses = normalizeStatuses(statuses);
	}

	public static RawTradeIngestFailureQuery between(
		String source,
		String lawdCd,
		String dealYmdFrom,
		String dealYmdTo
	) {
		return new RawTradeIngestFailureQuery(source, lawdCd, dealYmdFrom, dealYmdTo, DEFAULT_FAILURE_STATUSES);
	}

	public List<String> statusNames() {
		return statuses.stream()
			.map(Enum::name)
			.toList();
	}

	private static List<RawTradeIngestStatus> normalizeStatuses(List<RawTradeIngestStatus> statuses) {
		if (statuses == null || statuses.isEmpty()) {
			return DEFAULT_FAILURE_STATUSES;
		}
		LinkedHashSet<RawTradeIngestStatus> unique = new LinkedHashSet<>(statuses);
		if (!ALLOWED_STATUSES.containsAll(unique)) {
			throw new IllegalArgumentException(
				"statuses must be one of MATCH_FAILED, PARSE_FAILED, DUPLICATE"
			);
		}
		return List.copyOf(unique);
	}

	private static String validateDealYmd(String value, String fieldName) {
		if (value == null) {
			return null;
		}
		if (!DEAL_YMD.matcher(value).matches()) {
			throw new IllegalArgumentException(fieldName + " must use yyyyMM format");
		}
		return value;
	}

	private static String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
