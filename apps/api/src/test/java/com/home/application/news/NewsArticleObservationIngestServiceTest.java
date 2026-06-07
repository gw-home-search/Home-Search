package com.home.application.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NewsArticleObservationIngestServiceTest {

	@Test
	@DisplayName("news observation ingest는 source_key 중복을 건너뛰고 metadata-only command만 저장한다")
	void duplicateSourceKeyIsSkipped() {
		RecordingNewsArticleObservationRepository repository = new RecordingNewsArticleObservationRepository();
		NewsArticleObservationIngestService service = new NewsArticleObservationIngestService(repository);
		NewsArticleObservationCommand command = command("NAVER_NEWS:hash-1");

		NewsArticleObservationIngestResult result = service.ingest(List.of(command, command));

		assertThat(result).isEqualTo(new NewsArticleObservationIngestResult(2, 1, 1));
		assertThat(repository.savedCommands()).singleElement()
			.satisfies(saved -> {
				assertThat(saved.source()).isEqualTo("NAVER_NEWS");
				assertThat(saved.sourceKey()).isEqualTo("NAVER_NEWS:hash-1");
				assertThat(saved.title()).isEqualTo("강남 재건축 규제 완화");
				assertThat(saved.snippet()).isEqualTo("강남 재건축 정책 발표");
				assertThat(saved.rawProviderPayload()).doesNotContain("content", "body", "full_text", "html");
		});
	}

	@Test
	@DisplayName("news observation ingest는 빈 command 목록을 빈 결과로 반환한다")
	void emptyCommandsReturnEmptyResult() {
		RecordingNewsArticleObservationRepository repository = new RecordingNewsArticleObservationRepository();
		NewsArticleObservationIngestService service = new NewsArticleObservationIngestService(repository);

		NewsArticleObservationIngestResult result = service.ingest(List.of());

		assertThat(result).isEqualTo(NewsArticleObservationIngestResult.empty());
		assertThat(repository.savedCommands()).isEmpty();
	}

	private static NewsArticleObservationCommand command(String sourceKey) {
		return new NewsArticleObservationCommand(
			"NAVER_NEWS",
			sourceKey,
			"unknown",
			"강남 재건축 규제 완화",
			"https://example.com/news/1",
			"https://openapi.naver.com/v" + "1/search/news.json",
			"강남 재건축 정책 발표",
			OffsetDateTime.parse("2026-06-07T09:30:00+09:00"),
			OffsetDateTime.parse("2026-06-07T09:30:00+09:00"),
			OffsetDateTime.parse("2026-06-07T09:35:00+09:00"),
			OffsetDateTime.parse("2026-06-07T09:35:00+09:00"),
			null,
			LocalDate.parse("2026-06-07"),
			"{\"title\":\"강남 재건축 규제 완화\"}",
			"payload-hash",
			NewsArticleObservationStatus.OBSERVED,
			null
		);
	}

	private static final class RecordingNewsArticleObservationRepository implements NewsArticleObservationRepository {

		private final List<NewsArticleObservationCommand> savedCommands = new ArrayList<>();

		@Override
		public boolean insertIfAbsent(NewsArticleObservationCommand command) {
			if (savedCommands.stream().anyMatch(saved -> saved.source().equals(command.source())
				&& saved.sourceKey().equals(command.sourceKey()))) {
				return false;
			}
			savedCommands.add(command);
			return true;
		}

		List<NewsArticleObservationCommand> savedCommands() {
			return savedCommands;
		}
	}
}
