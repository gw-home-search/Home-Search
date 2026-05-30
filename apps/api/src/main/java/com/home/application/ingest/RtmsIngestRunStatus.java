package com.home.application.ingest;

import java.util.List;

public enum RtmsIngestRunStatus {
	COMPLETED,
	PARTIAL,
	FAILED;

	public static List<RtmsIngestRunStatus> all() {
		return List.of(COMPLETED, PARTIAL, FAILED);
	}
}
