package com.home.ingestcore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtmsIngestCoreBoundaryTest {

	@Test
	@DisplayName("RTMS ingest core는 API와 loader가 공유할 저장 invariant를 명시한다")
	void exposesSharedIngestInvariants() {
		assertEquals("raw-first", RtmsIngestCoreBoundary.RAW_FIRST);
		assertEquals("duplicate-safe", RtmsIngestCoreBoundary.DUPLICATE_SAFE);
		assertEquals("failed-match-queryable", RtmsIngestCoreBoundary.FAILED_MATCH_QUERYABLE);
	}
}
