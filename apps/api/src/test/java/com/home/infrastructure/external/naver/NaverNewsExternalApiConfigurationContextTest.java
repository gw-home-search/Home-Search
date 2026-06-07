package com.home.infrastructure.external.naver;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.news.NewsArticleObservationIngestService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class NaverNewsExternalApiConfigurationContextTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(
			NaverNewsExternalApiConfiguration.class,
			LateNewsIngestConfiguration.class
		)
		.withPropertyValues(
			"naver.news.client-id=client-id",
			"naver.news.client-token=client-token",
			"home.news.naver.enabled=true"
		);

	private final ApplicationContextRunner disabledContextRunner = new ApplicationContextRunner()
		.withUserConfiguration(
			NaverNewsExternalApiConfiguration.class,
			ObjectMapperConfiguration.class
		)
		.withPropertyValues(
			"naver.news.client-id=client-id",
			"naver.news.client-token=client-token"
		);

	@Test
	@DisplayName("Naver News one-shot runner는 ingest service bean 선언 순서와 무관하게 등록된다")
	void oneShotRunnerIsRegisteredWhenIngestServiceBeanDefinitionComesLater() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(NaverNewsOneShotIngestRunner.class);
			assertThat(context).hasBean("naverNewsOneShotIngestApplicationRunner");
			assertThat(context.getBean("naverNewsOneShotIngestApplicationRunner"))
				.isInstanceOf(ApplicationRunner.class);
		});
	}

	@Test
	@DisplayName("Naver News one-shot runner는 disabled 기본값이면 ingest service 없이도 컨텍스트를 시작한다")
	void disabledOneShotRunnerDoesNotRequireNewsPersistence() {
		disabledContextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(NaverNewsOneShotIngestRunner.class);
			assertThat(context).doesNotHaveBean("naverNewsOneShotIngestApplicationRunner");
		});
	}

	@Configuration(proxyBeanMethods = false)
	static class ObjectMapperConfiguration {

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class LateNewsIngestConfiguration extends ObjectMapperConfiguration {

		@Bean
		NewsArticleObservationIngestService newsArticleObservationIngestService() {
			return new NewsArticleObservationIngestService(command -> true);
		}
	}
}
