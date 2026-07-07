package com.home.infrastructure.scheduling.rtms;

import com.home.application.region.RegionUnitCntSyncResult;

record RtmsDailyRegionSyncResult(
	String status,
	String failureReason
) {

	private static final int MAX_FAILURE_REASON_LENGTH = 500;

	static RtmsDailyRegionSyncResult completed(RegionUnitCntSyncResult result) {
		return new RtmsDailyRegionSyncResult(result.partial() ? "PARTIAL" : "COMPLETED", null);
	}

	static RtmsDailyRegionSyncResult failed(RuntimeException exception) {
		String reason = exception.getClass().getSimpleName();
		if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
			reason += ": " + exception.getMessage();
		}
		return new RtmsDailyRegionSyncResult("FAILED", limitFailureReason(reason));
	}

	static RtmsDailyRegionSyncResult skipped() {
		return new RtmsDailyRegionSyncResult("SKIPPED", null);
	}

	boolean failed() {
		return "FAILED".equals(status);
	}

	boolean partial() {
		return "PARTIAL".equals(status);
	}

	boolean isSkipped() {
		return "SKIPPED".equals(status);
	}

	private static String limitFailureReason(String reason) {
		return reason.length() <= MAX_FAILURE_REASON_LENGTH
			? reason
			: reason.substring(0, MAX_FAILURE_REASON_LENGTH);
	}
}
