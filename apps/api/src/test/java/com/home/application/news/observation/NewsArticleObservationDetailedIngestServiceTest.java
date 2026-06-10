package com.home.application.news.observation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NewsArticleObservationDetailedIngestServiceTest {

	@Test
	@DisplayName("news observation ingest는 source_key별 신규/중복 outcome을 반환한다")
	void detailedIngestReturnsArticleOutcomes() {
		RecordingRepository repository = new RecordingRepository();
		NewsArticleObservationIngestService service = new NewsArticleObservationIngestService(repository);
		NewsArticleObservationCommand first = command("NAVER_NEWS:first");
		NewsArticleObservationCommand duplicate = command("NAVER_NEWS:first");

		NewsArticleObservationDetailedIngestResult result = service.ingestDetailed(List.of(first, duplicate));

		assertThat(result.result()).isEqualTo(new NewsArticleObservationIngestResult(2, 1, 1));
		assertThat(result.articleOutcomes())
			.extracting(NewsArticleObservationDetailedIngestResult.ArticleOutcome::observed)
			.containsExactly(true, false);
	}

	private static NewsArticleObservationCommand command(String sourceKey) {
		return new NewsArticleObservationCommand(
			"NAVER_NEWS",
			sourceKey,
			"example.com",
			"강남 재건축",
			"https://example.com/news/1",
			"https://n.news.naver.com/mnews/article/001/0000000001",
			"서울 강남 재건축",
			OffsetDateTime.parse("2026-06-10T03:30:00+09:00"),
			OffsetDateTime.parse("2026-06-10T03:30:00+09:00"),
			OffsetDateTime.parse("2026-06-09T19:00:00Z"),
			OffsetDateTime.parse("2026-06-09T19:00:00Z"),
			null,
			LocalDate.parse("2026-06-10"),
			"{\"title\":\"강남 재건축\"}",
			"payload-hash",
			NewsArticleObservationStatus.OBSERVED,
			null
		);
	}

	private static final class RecordingRepository implements NewsArticleObservationRepository {

		private final Set<String> sourceKeys = new HashSet<>();
		private final List<NewsArticleObservationCommand> savedCommands = new ArrayList<>();

		@Override
		public boolean insertIfAbsent(NewsArticleObservationCommand command) {
			savedCommands.add(command);
			return sourceKeys.add(command.sourceKey());
		}
	}
}
