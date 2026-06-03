package com.home.infrastructure.external.rtms;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.home.application.ingest.RtmsBackfillChunkRequest;

record RtmsNationwideBackfillPlan(
	String jobKey,
	List<String> lawdCds,
	String dealYmdFrom,
	String dealYmdTo
) {

	private static final DateTimeFormatter YEAR_MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

	RtmsNationwideBackfillPlan {
		jobKey = Objects.requireNonNull(jobKey, "jobKey is required").trim();
		if (jobKey.isBlank()) {
			throw new IllegalArgumentException("jobKey is required");
		}
		lawdCds = List.copyOf(Objects.requireNonNull(lawdCds, "lawdCds is required"));
		if (lawdCds.isEmpty()) {
			throw new IllegalArgumentException("lawdCd list is required");
		}
		for (String lawdCd : lawdCds) {
			new RtmsBackfillChunkRequest(lawdCd, dealYmdFrom);
		}
		YearMonth from = parseDealYmd(dealYmdFrom);
		YearMonth to = parseDealYmd(dealYmdTo);
		if (from.isAfter(to)) {
			throw new IllegalArgumentException("dealYmd from must be before or equal to dealYmd to");
		}
		dealYmdFrom = format(from);
		dealYmdTo = format(to);
	}

	List<String> dealYmds() {
		YearMonth current = parseDealYmd(dealYmdFrom);
		YearMonth end = parseDealYmd(dealYmdTo);
		List<String> values = new ArrayList<>();
		while (!current.isAfter(end)) {
			values.add(format(current));
			current = current.plusMonths(1);
		}
		return values;
	}

	List<RtmsBackfillChunkRequest> chunks() {
		List<RtmsBackfillChunkRequest> chunks = new ArrayList<>();
		for (String dealYmd : dealYmds()) {
			for (String lawdCd : lawdCds) {
				chunks.add(new RtmsBackfillChunkRequest(lawdCd, dealYmd));
			}
		}
		return chunks;
	}

	private static YearMonth parseDealYmd(String value) {
		try {
			return YearMonth.parse(Objects.requireNonNull(value, "dealYmd is required").trim(), YEAR_MONTH_FORMATTER);
		}
		catch (RuntimeException exception) {
			throw new IllegalArgumentException("dealYmd must be yyyyMM", exception);
		}
	}

	private static String format(YearMonth value) {
		return value.format(YEAR_MONTH_FORMATTER);
	}
}
