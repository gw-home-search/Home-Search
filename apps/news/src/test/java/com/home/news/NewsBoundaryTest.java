package com.home.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NewsBoundaryTest {

	private static final Path NEWS_CLEAN_DB_CUTOVER_SCRIPT = Path.of("ops/verify-news-clean-db-cutover.sh");

	@Test
	@DisplayName("news app은 later-scope runtime 경계를 가진다")
	void ownsLaterScopeRuntimeBoundary() {
		assertThat(NewsBoundary.APP_NAME).isEqualTo("home-search-news");
		assertThat(NewsBoundary.SCOPE).isEqualTo("later-scope");
	}

	@Test
	@DisplayName("news runtime은 later-scope라 기본값으로 실행되지 않는다")
	void newsRuntimeIsDisabledByDefault() {
		NewsRuntimeProperties properties = new NewsRuntimeProperties();

		assertThat(properties.isEnabled()).isFalse();
		assertThat(properties.boundary()).isEqualTo(NewsBoundary.SCOPE);
	}

	@Test
	@DisplayName("news app은 legacy news table clean DB 검증을 API 앱 대신 소유한다")
	void ownsLegacyNewsCleanDbVerifier() throws IOException {
		assertThat(NEWS_CLEAN_DB_CUTOVER_SCRIPT).exists();

		String content = Files.readString(NEWS_CLEAN_DB_CUTOVER_SCRIPT);

		assertThat(content).contains("public.news_article_observation");
		assertThat(content).contains("public.news_signal_feature");
		assertThat(content).contains("public.news_collection_run");
		assertThat(content).contains("HOME_NEWS_CUTOVER_DB");
		assertThat(content).contains("--verify-absent");
		assertThat(content).contains("--self-test");
		assertThat(content).doesNotContain("docker volume rm");
		assertThat(content).doesNotContain("docker volume prune");
		assertThat(content).doesNotContain("docker system prune");
		assertThat(content).doesNotContain("docker compose down -v");
		assertThat(content).doesNotContain("DROP DATABASE");
		assertThat(content).doesNotContain("TRUNCATE");
	}
}
