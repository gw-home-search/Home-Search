package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.home.application.news.NewsArticleObservationIngestService;
import com.home.application.news.NewsArticleObservationRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

class NewsPersistenceConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(
			NewsPersistenceConfiguration.class,
			LateJdbcClientConfiguration.class
		);

	@Test
	@DisplayName("News persistence bean은 JdbcClient bean 선언 순서와 무관하게 등록된다")
	void newsPersistenceBeansAreRegisteredWhenJdbcClientBeanDefinitionComesLater() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(NewsArticleObservationRepository.class);
			assertThat(context).hasSingleBean(NewsArticleObservationIngestService.class);
		});
	}

	@Configuration(proxyBeanMethods = false)
	static class LateJdbcClientConfiguration {

		@Bean
		JdbcClient jdbcClient() {
			return mock(JdbcClient.class);
		}
	}
}
