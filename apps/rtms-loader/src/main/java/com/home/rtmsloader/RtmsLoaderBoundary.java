package com.home.rtmsloader;

/**
 * RTMS 초기 적재와 historical bulk load runtime의 소유권 경계입니다.
 */
public final class RtmsLoaderBoundary {

	public static final String APP_NAME = "home-search-rtms-loader";
	public static final String INITIAL_LOAD_MODE = "initial-load";
	public static final String MONTHLY_BULK_MODE = "monthly-bulk";

	private RtmsLoaderBoundary() {
	}
}
