package com.home.application.ingest;

@FunctionalInterface
public interface RtmsIngestRunRepository {

	RtmsIngestRunRecord save(RtmsIngestRunRecord record);

	static RtmsIngestRunRepository noop() {
		return record -> record;
	}
}
