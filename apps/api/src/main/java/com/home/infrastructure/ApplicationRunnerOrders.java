package com.home.infrastructure;

public final class ApplicationRunnerOrders {

	public static final int RTMS_ONE_SHOT_INGEST = 100;
	public static final int RAW_INGEST_RECONCILIATION = 150;
	public static final int COORDINATE_READINESS = 200;
	public static final int NEWS_ONE_SHOT_INGEST = 300;
	public static final int NEWS_RELEVANCE_GATE = 350;
	public static final int NEWS_SIGNAL_FEATURE_EXTRACTION = 360;
	public static final int NEWS_OBSERVATION_CLEANUP = 370;
	public static final int NEWS_OBSIDIAN_EXPORT = 380;

	private ApplicationRunnerOrders() {
	}
}
