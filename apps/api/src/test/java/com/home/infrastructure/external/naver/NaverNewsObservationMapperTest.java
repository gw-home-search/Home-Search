package com.home.infrastructure.external.naver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.news.NewsArticleObservationCommand;
import com.home.application.news.NewsArticleObservationStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NaverNewsObservationMapperTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final Clock clock = Clock.fixed(Instant.parse("2026-06-07T00:35:00Z"), ZoneOffset.UTC);
	private final NaverNewsSearchResponseParser parser = new NaverNewsSearchResponseParser(objectMapper);
	private final NaverNewsObservationMapper mapper = new NaverNewsObservationMapper(clock, objectMapper);

	@Test
	@DisplayName("Naver News item은 본문 저장 없이 sanitized metadata observation command로 변환된다")
	void mapsNaverNewsItemToMetadataOnlyObservationCommand() {
		NaverNewsSearchPage page = parser.parse("""
			{
			  "lastBuildDate": "Sun, 07 Jun 2026 09:40:00 +0900",
			  "total": 1,
			  "start": 1,
			  "display": 1,
			  "items": [
			    {
			      "title": "강남 <b>재건축</b> 규제 &amp; 완화",
			      "originallink": "https://www.example.com/news/real-estate?id=100#section",
			      "link": "https://n.news.naver.com/mnews/article/001/0000000001",
			      "description": "서울 <b>강남</b> 재건축 정책 발표",
			      "pubDate": "Sun, 07 Jun 2026 09:30:00 +0900",
			      "content": "article body must not be stored"
			    }
			  ]
			}
			""");

		List<NewsArticleObservationCommand> commands = mapper.toObservationCommands(page);

		assertThat(commands).singleElement()
			.satisfies(command -> {
				assertThat(command.source()).isEqualTo("NAVER_NEWS");
				assertThat(command.sourceKey()).startsWith("NAVER_NEWS:");
				assertThat(command.publisher()).isEqualTo("example.com");
				assertThat(command.title()).isEqualTo("강남 재건축 규제 & 완화");
				assertThat(command.url()).isEqualTo("https://www.example.com/news/real-estate?id=100");
				assertThat(command.providerUrl()).isEqualTo("https://n.news.naver.com/mnews/article/001/0000000001");
				assertThat(command.snippet()).isEqualTo("서울 강남 재건축 정책 발표");
				assertThat(command.publishedAt()).isEqualTo("2026-06-07T09:30:00+09:00");
				assertThat(command.providerPubAt()).isEqualTo(command.publishedAt());
				assertThat(command.firstSeenAt()).isEqualTo("2026-06-07T00:35:00Z");
				assertThat(command.newsDateKst()).isEqualTo("2026-06-07");
				assertThat(command.ingestStatus()).isEqualTo(NewsArticleObservationStatus.OBSERVED);
				assertThat(command.rawProviderPayload())
					.contains("\"title\":\"강남 재건축 규제 & 완화\"")
					.doesNotContain("<b>", "content", "body", "full_text", "html");
				assertThat(command.payloadHash()).hasSize(64);
			});
	}

	@Test
	@DisplayName("Naver News source_key는 canonical article URL 기준으로 안정적으로 생성된다")
	void sourceKeyUsesCanonicalArticleUrl() {
		NaverNewsSearchPage firstPage = parser.parse(pageFor("https://www.example.com/news/1#first"));
		NaverNewsSearchPage secondPage = parser.parse(pageFor("https://www.example.com/news/1"));

		String firstSourceKey = mapper.toObservationCommands(firstPage).get(0).sourceKey();
		String secondSourceKey = mapper.toObservationCommands(secondPage).get(0).sourceKey();

		assertThat(firstSourceKey).isEqualTo(secondSourceKey);
	}

	@Test
	@DisplayName("Naver News item은 originallink가 없으면 provider link를 article URL로 사용한다")
	void blankOriginallinkFallsBackToProviderLink() {
		NaverNewsSearchPage page = parser.parse("""
			{
			  "items": [
			    {
			      "title": "강남 재건축",
			      "originallink": "",
			      "link": "not a url#fragment"
			    }
			  ]
			}
			""");

		NewsArticleObservationCommand command = mapper.toObservationCommands(page).get(0);

		assertThat(command.url()).isEqualTo("not a url");
		assertThat(command.providerUrl()).isEqualTo("not a url");
		assertThat(command.publisher()).isEqualTo("unknown");
		assertThat(command.snippet()).isEmpty();
		assertThat(command.publishedAt()).isNull();
		assertThat(command.rawProviderPayload())
			.contains("\"link\":\"not a url\"")
			.doesNotContain("description", "pubDate");
	}

	@Test
	@DisplayName("Naver News page는 null item 목록을 빈 목록으로 정규화한다")
	void nullItemsAreNormalizedToEmptyList() {
		NaverNewsSearchPage page = new NaverNewsSearchPage(null, 0, 1, 0, null);

		assertThat(page.items()).isEmpty();
	}

	private static String pageFor(String originallink) {
		return """
			{
			  "items": [
			    {
			      "title": "강남 재건축",
			      "originallink": "%s",
			      "link": "https://n.news.naver.com/mnews/article/001/0000000001",
			      "description": "서울 강남 재건축",
			      "pubDate": "Sun, 07 Jun 2026 09:30:00 +0900"
			    }
			  ]
			}
			""".formatted(originallink);
	}
}
