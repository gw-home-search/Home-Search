package com.home.infrastructure.external.naver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.news.NewsArticleObservationCommand;
import com.home.application.news.NewsArticleObservationIngestResult;
import com.home.application.news.NewsArticleObservationIngestService;
import com.home.application.news.NewsArticleObservationRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NaverNewsOneShotIngestRunnerTest {

	@Test
	@DisplayName("Naver News one-shot runner는 fetched page를 observation ingest service로 전달한다")
	void fetchesPageAndIngestsObservationCommands() {
		RecordingNewsArticleObservationRepository repository = new RecordingNewsArticleObservationRepository();
		NewsArticleObservationIngestService ingestService = new NewsArticleObservationIngestService(repository);
		NaverNewsOneShotIngestRunner runner = new NaverNewsOneShotIngestRunner(
			request -> new NaverNewsSearchResponseParser(new ObjectMapper()).parse("""
				{
				  "items": [
				    {
				      "title": "강남 재건축",
				      "originallink": "https://example.com/news/1",
				      "link": "https://n.news.naver.com/mnews/article/001/0000000001",
				      "description": "서울 강남 재건축",
				      "pubDate": "Sun, 07 Jun 2026 09:30:00 +0900"
				    }
				  ]
				}
				"""),
			new NaverNewsObservationMapper(
				Clock.fixed(Instant.parse("2026-06-07T00:35:00Z"), ZoneOffset.UTC),
				new ObjectMapper()
			),
			ingestService
		);

		NewsArticleObservationIngestResult result = runner.ingest(
			new NaverNewsSearchRequest("강남 재건축", 1, 1, "date")
		);

		assertThat(result).isEqualTo(new NewsArticleObservationIngestResult(1, 1, 0));
		assertThat(repository.savedCommands()).singleElement()
			.extracting(NewsArticleObservationCommand::source)
			.isEqualTo("NAVER_NEWS");
	}

	private static final class RecordingNewsArticleObservationRepository implements NewsArticleObservationRepository {

		private final List<NewsArticleObservationCommand> savedCommands = new ArrayList<>();

		@Override
		public boolean insertIfAbsent(NewsArticleObservationCommand command) {
			savedCommands.add(command);
			return true;
		}

		List<NewsArticleObservationCommand> savedCommands() {
			return savedCommands;
		}
	}
}
