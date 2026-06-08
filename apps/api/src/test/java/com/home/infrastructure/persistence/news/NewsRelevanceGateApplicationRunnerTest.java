package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.home.application.news.relevance.NewsArticleRelevanceGateResult;
import com.home.application.news.relevance.NewsArticleRelevanceGateService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NewsRelevanceGateApplicationRunnerTest {

	@Test
	@DisplayName("news relevance gate runner는 disabled 설정이면 평가를 실행하지 않는다")
	void disabledRunnerDoesNotEvaluate() {
		NewsArticleRelevanceGateService service = mock(NewsArticleRelevanceGateService.class);
		NewsRelevanceGateApplicationRunner runner = new NewsRelevanceGateApplicationRunner(
			service,
			new NewsRelevanceGateProperties(false, 100)
		);

		runner.run(null);

		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("news relevance gate runner는 enabled 설정이면 configured limit으로 평가한다")
	void enabledRunnerEvaluatesWithLimit() {
		NewsArticleRelevanceGateService service = mock(NewsArticleRelevanceGateService.class);
		when(service.evaluateObserved(25))
			.thenReturn(new NewsArticleRelevanceGateResult(25, 20, 3, 2, 2, 0));
		NewsRelevanceGateApplicationRunner runner = new NewsRelevanceGateApplicationRunner(
			service,
			new NewsRelevanceGateProperties(true, 25)
		);

		runner.run(null);

		verify(service).evaluateObserved(25);
		assertThat(runner.getOrder()).isEqualTo(ApplicationRunnerOrders.NEWS_RELEVANCE_GATE);
		assertThat(runner.getOrder()).isGreaterThan(ApplicationRunnerOrders.NEWS_ONE_SHOT_INGEST);
	}
}
