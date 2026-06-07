package com.home.application.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NewsArticleObservationCleanupServiceTest {

	private static final OffsetDateTime RETENTION_CUTOFF = OffsetDateTime.parse("2026-06-05T00:00:00Z");

	@Test
	@DisplayName("news observation cleanup service는 retention cutoff 이전 provider payload 정리 결과를 집계한다")
	void purgesProviderPayloadsAndCountsRows() {
		RecordingNewsArticleObservationCleanupRepository repository = new RecordingNewsArticleObservationCleanupRepository(
			List.of(
				record(1L, "naver-news:featured", "FEATURED"),
				record(2L, "naver-news:skipped", "SKIPPED_IRRELEVANT")
			)
		);
		NewsArticleObservationCleanupService service = new NewsArticleObservationCleanupService(repository);

		NewsArticleObservationCleanupResult result = service.cleanup(RETENTION_CUTOFF);

		assertThat(result.purged()).isEqualTo(2);
		assertThat(result.records()).extracting(NewsArticleObservationCleanupRecord::sourceKey)
			.containsExactly("naver-news:featured", "naver-news:skipped");
		assertThat(repository.retentionCutoffs).containsExactly(RETENTION_CUTOFF);
	}

	private static NewsArticleObservationCleanupRecord record(long id, String sourceKey, String ingestStatus) {
		return new NewsArticleObservationCleanupRecord(
			id,
			"NAVER_NEWS",
			sourceKey,
			ingestStatus,
			"PURGE_PAYLOAD"
		);
	}

	private static class RecordingNewsArticleObservationCleanupRepository
		implements NewsArticleObservationCleanupRepository {

		private final List<NewsArticleObservationCleanupRecord> records;
		private final List<OffsetDateTime> retentionCutoffs = new ArrayList<>();

		RecordingNewsArticleObservationCleanupRepository(List<NewsArticleObservationCleanupRecord> records) {
			this.records = records;
		}

		@Override
		public List<NewsArticleObservationCleanupRecord> purgeProviderPayloads(OffsetDateTime retentionCutoff) {
			retentionCutoffs.add(retentionCutoff);
			return records;
		}
	}
}
