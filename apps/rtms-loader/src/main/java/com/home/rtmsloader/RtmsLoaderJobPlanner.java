package com.home.rtmsloader;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.home.ingestcore.rtms.RtmsDealMonth;
import com.home.ingestcore.rtms.RtmsLawdCode;

public class RtmsLoaderJobPlanner {

	private static final DateTimeFormatter DEAL_YMD_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");
	private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");

	private final Clock clock;

	public RtmsLoaderJobPlanner(Clock clock) {
		this.clock = Objects.requireNonNull(clock);
	}

	public RtmsLoaderJobPlan plan(RtmsLoaderJobRequest request) {
		Objects.requireNonNull(request, "request is required");
		List<RtmsLoaderMonthRequest> months = new ArrayList<>();
		String baseDealYmd = baseDealYmd(request);
		YearMonth baseMonth = YearMonth.parse(baseDealYmd, DEAL_YMD_FORMATTER);
		for (String lawdCd : request.lawdCds()) {
			String normalizedLawdCd = normalizeLawdCd(lawdCd);
			for (int offset = 0; offset <= request.lookbackMonths(); offset++) {
				String dealYmd = baseMonth.minusMonths(offset).format(DEAL_YMD_FORMATTER);
				months.add(new RtmsLoaderMonthRequest(normalizedLawdCd, dealYmd));
			}
		}
		return new RtmsLoaderJobPlan(request.mode(), months);
	}

	private String baseDealYmd(RtmsLoaderJobRequest request) {
		if (request.baseDealYmd() == null || request.baseDealYmd().isBlank()) {
			return YearMonth.now(clock.withZone(DEFAULT_ZONE)).format(DEAL_YMD_FORMATTER);
		}
		return normalizeDealYmd(request.baseDealYmd());
	}

	private String normalizeLawdCd(String lawdCd) {
		try {
			return RtmsLawdCode.of(lawdCd).value();
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("lawdCd is invalid: " + lawdCd, exception);
		}
	}

	private String normalizeDealYmd(String dealYmd) {
		try {
			return RtmsDealMonth.of(dealYmd).value();
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("dealYmd is invalid: " + dealYmd, exception);
		}
	}
}
