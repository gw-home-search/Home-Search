package com.home.application.ingest.run;

public interface RtmsIngestRunReportRepository {

	/**
	 * Summarize stored RTMS ingest run outcomes without exposing raw payload or source_key.
	 *
	 * @param query lawdCd, dealYmd range, status, and recent run limit criteria
	 * @return total counts, status counts, and recent runs
	 */
	RtmsIngestRunReport summarize(RtmsIngestRunReportQuery query);
}
