package com.home.infrastructure.external.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.news.collection.NewsCollectionRepository;
import com.home.application.news.observation.NewsArticleObservationIngestService;
import com.home.application.news.relevance.NewsArticleRelevanceGateService;
import com.home.application.news.signal.NewsSignalFeatureExtractionService;
import com.home.application.news.export.NewsSignalObsidianExportService;
import com.home.infrastructure.scheduling.news.NaverNewsDailyPipelineConfiguration;
import com.home.infrastructure.scheduling.news.NaverNewsDailyPipelineRunner;
import com.home.infrastructure.scheduling.news.NaverNewsDailyPipelineScheduler;

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

	private final ApplicationContextRunner pipelineContextRunner = new ApplicationContextRunner()
		.withUserConfiguration(
			NaverNewsExternalApiConfiguration.class,
			PipelineDependenciesConfiguration.class
		)
		.withPropertyValues(
			"naver.news.client-id=client-id",
			"naver.news.client-token=client-token",
			"home.news.pipeline.enabled=true",
			"home.news.naver.enabled=true",
			"home.news.naver.query=강남 재건축",
			"home.news.obsidian.export.output-root=/tmp/home-search-obsidian-test"
		);

	private final ApplicationContextRunner dailyPipelineContextRunner = new ApplicationContextRunner()
		.withUserConfiguration(
			NaverNewsExternalApiConfiguration.class,
			NaverNewsDailyPipelineConfiguration.class,
			DailyPipelineDependenciesConfiguration.class
		)
		.withPropertyValues(
			"naver.news.client-id=client-id",
			"naver.news.client-token=client-token",
			"home.news.pipeline.daily.enabled=true",
			"home.news.pipeline.daily.obsidian-output-root=/tmp/home-search-obsidian-test"
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

	@Test
	@DisplayName("Naver News signal pipeline runner는 pipeline 설정이면 개별 one-shot application runner 대신 등록된다")
	void pipelineRunnerIsRegisteredInsteadOfIndividualOneShotApplicationRunner() {
		pipelineContextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(NaverNewsOneShotIngestRunner.class);
			assertThat(context).hasBean("naverNewsSignalPipelineApplicationRunner");
			assertThat(context.getBean("naverNewsSignalPipelineApplicationRunner"))
				.isInstanceOf(ApplicationRunner.class);
			assertThat(context).doesNotHaveBean("naverNewsOneShotIngestApplicationRunner");
		});
	}

	@Test
	@DisplayName("Naver News daily pipeline 설정은 one-shot runner를 재사용하고 scheduler를 등록한다")
	void dailyPipelineRegistersSchedulerAndReusesOneShotRunner() {
		dailyPipelineContextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(NaverNewsOneShotIngestRunner.class);
			assertThat(context).hasSingleBean(NaverNewsDailyPipelineRunner.class);
			assertThat(context).hasSingleBean(NaverNewsDailyPipelineScheduler.class);
			assertThat(context).doesNotHaveBean("naverNewsSignalPipelineApplicationRunner");
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

	@Configuration(proxyBeanMethods = false)
	static class PipelineDependenciesConfiguration extends LateNewsIngestConfiguration {

		@Bean
		NewsArticleRelevanceGateService newsArticleRelevanceGateService() {
			return mock(NewsArticleRelevanceGateService.class);
		}

		@Bean
		NewsSignalFeatureExtractionService newsSignalFeatureExtractionService() {
			return mock(NewsSignalFeatureExtractionService.class);
		}

		@Bean
		NewsSignalObsidianExportService newsSignalObsidianExportService() {
			return mock(NewsSignalObsidianExportService.class);
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class DailyPipelineDependenciesConfiguration extends PipelineDependenciesConfiguration {

		@Bean
		NewsCollectionRepository newsCollectionRepository() {
			return mock(NewsCollectionRepository.class);
		}
	}
}
