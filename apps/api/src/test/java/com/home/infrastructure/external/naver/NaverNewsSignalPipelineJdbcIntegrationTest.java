package com.home.infrastructure.external.naver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.home.HomeSearchApiApplication;
import com.home.application.news.NewsSignalFeatureExtractionPolicy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = {
	HomeSearchApiApplication.class,
	NaverNewsSignalPipelineJdbcIntegrationTest.FakeNaverNewsClientConfiguration.class
})
@Testcontainers
class NaverNewsSignalPipelineJdbcIntegrationTest {

	private static final Path OBSIDIAN_OUTPUT_ROOT = createObsidianOutputRoot();

	@Container
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
		DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
	);

	@Autowired
	private JdbcClient jdbcClient;

	@Autowired
	private NaverNewsSignalPipelineApplicationRunner pipelineRunner;

	@DynamicPropertySource
	static void testProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
		registry.add("spring.flyway.enabled", () -> "true");
		registry.add("spring.flyway.locations", () -> "classpath:db/migration/api");
		registry.add("spring.flyway.clean-disabled", () -> "true");
		registry.add("home.coordinate-source.db.jdbc-url", POSTGRES::getJdbcUrl);
		registry.add("home.coordinate-source.db.username", POSTGRES::getUsername);
		registry.add("home.coordinate-source.db.password", POSTGRES::getPassword);
		registry.add("naver.news.client-id", () -> "client-id");
		registry.add("naver.news.client-token", () -> "client-token");
		registry.add("home.news.pipeline.enabled", () -> "true");
		registry.add("home.news.naver.enabled", () -> "true");
		registry.add("home.news.naver.query", () -> "강남 재건축");
		registry.add("home.news.naver.display", () -> "2");
		registry.add("home.news.naver.start", () -> "1");
		registry.add("home.news.naver.sort", () -> "date");
		registry.add("home.news.relevance.limit", () -> "10");
		registry.add("home.news.signal.extraction.limit", () -> "10");
		registry.add("home.news.obsidian.export.output-root", OBSIDIAN_OUTPUT_ROOT::toString);
		registry.add("home.news.obsidian.export.max-rows", () -> "10");
	}

	@Test
	@DisplayName("Naver News signal pipeline은 fake response를 DB feature와 Obsidian daily note까지 연결한다")
	void pipelineConnectsFakeResponseToJdbcFeaturesAndObsidianDailyNote() {
		assertThat(observationCount()).isEqualTo(2L);
		assertThat(featureCount()).isEqualTo(2L);
		assertThat(statusCount("FEATURED")).isEqualTo(2L);

		Path dailyNote = dailyNotePath();
		assertThat(dailyNote).exists();
		assertThat(readString(dailyNote))
			.contains(
				"generated_from: news_signal_dataset_view",
				"feature_count: 2",
				"article_count: 2",
				"title_keywords:",
				"source_key: NAVER_NEWS:",
				"extraction_version: " + NewsSignalFeatureExtractionPolicy.DEFAULT_EXTRACTION_VERSION
			)
			.contains("강남", "재건축", "서초", "아파트", "거래량", "상승")
			.doesNotContain(
				"서울 강남 재건축 정책 발표",
				"서초 매매 시장 회복세",
				"raw_provider_payload",
				"content",
				"body",
				"full_text",
				"html"
			);

		pipelineRunner.run(null);

		assertThat(observationCount()).isEqualTo(2L);
		assertThat(featureCount()).isEqualTo(2L);
		assertThat(statusCount("FEATURED")).isEqualTo(2L);
		assertThat(readString(dailyNote)).contains("feature_count: 2", "article_count: 2");
	}

	private static Path createObsidianOutputRoot() {
		try {
			return Files.createTempDirectory("home-search-news-obsidian-");
		}
		catch (IOException exception) {
			throw new IllegalStateException("Failed to create temp Obsidian output root", exception);
		}
	}

	private Path dailyNotePath() {
		return OBSIDIAN_OUTPUT_ROOT
			.resolve("news-signals")
			.resolve("daily")
			.resolve(exportedFeatureDate() + ".md");
	}

	private LocalDate exportedFeatureDate() {
		return jdbcClient.sql("SELECT min(feature_date_kst) FROM news_signal_feature")
			.query(LocalDate.class)
			.single();
	}

	private static String readString(Path path) {
		try {
			return Files.readString(path);
		}
		catch (IOException exception) {
			throw new IllegalStateException("Failed to read Obsidian daily note", exception);
		}
	}

	private Long observationCount() {
		return jdbcClient.sql("SELECT count(*) FROM news_article_observation")
			.query(Long.class)
			.single();
	}

	private Long featureCount() {
		return jdbcClient.sql("SELECT count(*) FROM news_signal_feature")
			.query(Long.class)
			.single();
	}

	private Long statusCount(String status) {
		return jdbcClient.sql("""
			SELECT count(*)
			FROM news_article_observation
			WHERE ingest_status = :status
			""")
			.param("status", status)
			.query(Long.class)
			.single();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeNaverNewsClientConfiguration {

		@Bean
		@Primary
		NaverNewsSearchClient fakeNaverNewsSearchClient() {
			return request -> new NaverNewsSearchPage(
				OffsetDateTime.parse("2026-06-07T09:40:00+09:00"),
				2,
				request.start(),
				request.display(),
				List.of(
					new NaverNewsSearchItem(
						"강남 재건축 규제 완화에 아파트 집값 상승",
						"https://example.com/news/gangnam-reconstruction",
						"https://n.news.naver.com/mnews/article/001/0000000001",
						"서울 강남 재건축 정책 발표",
						"Sun, 07 Jun 2026 09:30:00 +0900"
					),
					new NaverNewsSearchItem(
						"서초 아파트 거래량 상승",
						"https://example.com/news/seocho-volume",
						"https://n.news.naver.com/mnews/article/001/0000000002",
						"서초 매매 시장 회복세",
						"Sun, 07 Jun 2026 09:35:00 +0900"
					)
				)
			);
		}
	}
}
