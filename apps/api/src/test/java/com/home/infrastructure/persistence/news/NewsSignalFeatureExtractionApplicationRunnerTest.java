package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.home.application.news.signal.NewsSignalFeatureExtractionResult;
import com.home.application.news.signal.NewsSignalFeatureExtractionService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NewsSignalFeatureExtractionApplicationRunnerTest {

	@Test
	@DisplayName("news signal feature extraction runner는 disabled 설정이면 추출을 실행하지 않는다")
	void disabledRunnerDoesNotExtract() {
		NewsSignalFeatureExtractionService service = mock(NewsSignalFeatureExtractionService.class);
		NewsSignalFeatureExtractionApplicationRunner runner = new NewsSignalFeatureExtractionApplicationRunner(
			service,
			new NewsSignalFeatureExtractionProperties(false, 100)
		);

		runner.run(null);

		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("news signal feature extraction runner는 enabled 설정이면 configured limit으로 추출한다")
	void enabledRunnerExtractsWithLimit() {
		NewsSignalFeatureExtractionService service = mock(NewsSignalFeatureExtractionService.class);
		when(service.extractPending(25))
			.thenReturn(new NewsSignalFeatureExtractionResult(25, 23, 23, 2));
		NewsSignalFeatureExtractionApplicationRunner runner = new NewsSignalFeatureExtractionApplicationRunner(
			service,
			new NewsSignalFeatureExtractionProperties(true, 25)
		);

		runner.run(null);

		verify(service).extractPending(25);
		assertThat(runner.getOrder()).isEqualTo(ApplicationRunnerOrders.NEWS_SIGNAL_FEATURE_EXTRACTION);
		assertThat(runner.getOrder()).isGreaterThan(ApplicationRunnerOrders.NEWS_RELEVANCE_GATE);
	}
}
