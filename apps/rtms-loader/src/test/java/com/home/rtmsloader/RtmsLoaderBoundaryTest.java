package com.home.rtmsloader;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.ingestcore.RtmsIngestCoreBoundary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtmsLoaderBoundaryTest {

	@Test
	@DisplayName("RTMS loader app은 초기 적재와 historical bulk load mode를 소유한다")
	void ownsInitialLoadAndHistoricalBulkLoadModes() {
		assertThat(RtmsLoaderBoundary.APP_NAME).isEqualTo("home-search-rtms-loader");
		assertThat(RtmsLoaderBoundary.INITIAL_LOAD_MODE).isEqualTo("initial-load");
		assertThat(RtmsLoaderBoundary.MONTHLY_BULK_MODE).isEqualTo("monthly-bulk");
	}

	@Test
	@DisplayName("RTMS loader app은 API와 같은 ingest core invariant를 참조한다")
	void dependsOnSharedRtmsIngestCore() {
		assertThat(RtmsIngestCoreBoundary.RAW_FIRST).isEqualTo("raw-first");
		assertThat(RtmsIngestCoreBoundary.DUPLICATE_SAFE).isEqualTo("duplicate-safe");
		assertThat(RtmsIngestCoreBoundary.FAILED_MATCH_QUERYABLE).isEqualTo("failed-match-queryable");
	}
}
