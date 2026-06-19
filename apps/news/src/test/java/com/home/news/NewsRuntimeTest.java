package com.home.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NewsRuntimeTest {

	private static final Path NEWS_CLEAN_DB_CUTOVER_SCRIPT = Path.of("ops/verify-news-clean-db-cutover.sh");
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(NewsRuntimeConfiguration.class)
		.withBean(ObjectMapper.class, ObjectMapper::new);

	@Test
	@DisplayName("news runtime은 later-scope라 기본값으로 실행되지 않는다")
	void newsRuntimeIsDisabledByDefault() {
		NewsRuntimeProperties properties = new NewsRuntimeProperties();

		assertThat(properties.isEnabled()).isFalse();
	}

	@Test
	@DisplayName("news disabled이면 run-once enabled여도 runner bean을 만들지 않는다")
	void runOnceRunnerRequiresNewsRuntimeEnabled() {
		contextRunner
			.withPropertyValues(
				"home.news.enabled=false",
				"home.news.run-once.enabled=true",
				"home.news.run-once.query-text=강남 재건축"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(ApplicationRunner.class);
			});
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
