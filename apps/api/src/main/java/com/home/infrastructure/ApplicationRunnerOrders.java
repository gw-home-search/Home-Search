package com.home.infrastructure;

public final class ApplicationRunnerOrders {

	private static final int INGEST_PHASE_STEP = 50;
	private static final int MAJOR_PIPELINE_STEP = 100;
	private static final int NEWS_COLLECTION_STEP = 50;
	private static final int NEWS_PROCESSING_STEP = 10;

	public static final int RTMS_ONE_SHOT_INGEST = 100;
	public static final int RAW_INGEST_RECONCILIATION = RTMS_ONE_SHOT_INGEST + INGEST_PHASE_STEP;
	public static final int COORDINATE_READINESS = RAW_INGEST_RECONCILIATION + INGEST_PHASE_STEP;
	public static final int REGION_UNIT_CNT_SYNC = COORDINATE_READINESS + INGEST_PHASE_STEP;
	public static final int NEWS_SIGNAL_PIPELINE = COORDINATE_READINESS + MAJOR_PIPELINE_STEP;
	public static final int NEWS_ONE_SHOT_INGEST = NEWS_SIGNAL_PIPELINE;
	public static final int NEWS_RELEVANCE_GATE = NEWS_ONE_SHOT_INGEST + NEWS_COLLECTION_STEP;
	public static final int NEWS_SIGNAL_FEATURE_EXTRACTION = NEWS_RELEVANCE_GATE + NEWS_PROCESSING_STEP;
	public static final int NEWS_OBSERVATION_CLEANUP = NEWS_SIGNAL_FEATURE_EXTRACTION + NEWS_PROCESSING_STEP;
	public static final int NEWS_OBSIDIAN_EXPORT = NEWS_OBSERVATION_CLEANUP + NEWS_PROCESSING_STEP;

	private ApplicationRunnerOrders() {
	}
}
