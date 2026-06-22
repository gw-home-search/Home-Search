package com.home.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NewsRuntimeTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(NewsRuntimeConfiguration.class)
		.withBean(ObjectMapper.class, ObjectMapper::new);

	@Test
	@DisplayName("news runtime은 later-scope라 기본값으로 실행되지 않는다")
	void newsRuntimeIsDisabledByDefault() {
		NewsRuntimeProperties properties = new NewsRuntimeProperties();

		assertThat(properties.isEnabled()).isFalse();
		assertThat(properties.getRegionMonthSignals().isEnabled()).isFalse();
	}

	@Test
	@DisplayName("news disabled이면 region-month-signal enabled여도 runner bean을 만들지 않는다")
	void runnerRequiresNewsRuntimeEnabled() {
		contextRunner
			.withPropertyValues(
				"home.news.enabled=false",
				"home.news.region-month-signals.enabled=true"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(ApplicationRunner.class);
			});
	}

	@Test
	@DisplayName("CSV aggregate 생성 mode는 database 설정 없이 runner를 만들 수 있다")
	void generateModeDoesNotRequireDatabase() {
		contextRunner
			.withPropertyValues(
				"home.news.enabled=true",
				"home.news.region-month-signals.enabled=true",
				"home.news.region-month-signals.mode=GENERATE_CSV_REGION_MONTH_SIGNALS"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(ApplicationRunner.class);
				assertThat(context).doesNotHaveBean(javax.sql.DataSource.class);
			});
	}
}
