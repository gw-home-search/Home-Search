package com.home.infrastructure;

public final class ApplicationRunnerOrders {

	private static final int INGEST_PHASE_STEP = 50;

	public static final int RTMS_ONE_SHOT_INGEST = 100;
	public static final int RAW_INGEST_RECONCILIATION = RTMS_ONE_SHOT_INGEST + INGEST_PHASE_STEP;
	public static final int COORDINATE_READINESS = RAW_INGEST_RECONCILIATION + INGEST_PHASE_STEP;
	public static final int REGION_UNIT_CNT_SYNC = COORDINATE_READINESS + INGEST_PHASE_STEP;

	private ApplicationRunnerOrders() {
	}
}
