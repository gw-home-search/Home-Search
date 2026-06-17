package com.home.ingestcore;

/**
 * API daily refresh와 RTMS loader가 공유해야 하는 ingest invariant 경계입니다.
 */
public final class RtmsIngestCoreBoundary {

	public static final String RAW_FIRST = "raw-first";
	public static final String DUPLICATE_SAFE = "duplicate-safe";
	public static final String FAILED_MATCH_QUERYABLE = "failed-match-queryable";

	private RtmsIngestCoreBoundary() {
	}
}
