package com.home.application.ingest.backfill;

import java.util.Objects;
import java.util.regex.Pattern;

public record RtmsBackfillChunkRequest(String lawdCd, String dealYmd) {

	private static final Pattern LAWD_CD_PATTERN = Pattern.compile("\\d{5}");
	private static final Pattern DEAL_YMD_PATTERN = Pattern.compile("\\d{6}");

	public RtmsBackfillChunkRequest {
		lawdCd = Objects.requireNonNull(lawdCd, "lawdCd is required").trim();
		dealYmd = Objects.requireNonNull(dealYmd, "dealYmd is required").trim();
		if (!LAWD_CD_PATTERN.matcher(lawdCd).matches()) {
			throw new IllegalArgumentException("lawdCd must be a 5 digit RTMS code");
		}
		if (!DEAL_YMD_PATTERN.matcher(dealYmd).matches()) {
			throw new IllegalArgumentException("dealYmd must be yyyyMM");
		}
	}
}
