package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.home.application.news.observation.NewsArticleObservationCleanupResult;
import com.home.application.news.observation.NewsArticleObservationCleanupService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NewsArticleObservationCleanupApplicationRunnerTest {

	private static final Clock FIXED_CLOCK = Clock.fixed(
		Instant.parse("2026-06-07T00:00:00Z"),
		ZoneOffset.UTC
	);

	@Test
	@DisplayName("news observation cleanup runner는 disabled 설정이면 cleanup을 실행하지 않는다")
	void disabledRunnerDoesNotCleanup() {
		NewsArticleObservationCleanupService service = mock(NewsArticleObservationCleanupService.class);
		NewsArticleObservationCleanupApplicationRunner runner = new NewsArticleObservationCleanupApplicationRunner(
			service,
			new NewsArticleObservationCleanupProperties(false, Duration.ofDays(2)),
			FIXED_CLOCK
		);

		runner.run(null);

		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("news observation cleanup runner는 enabled 설정이면 retention window 기준 cutoff로 cleanup을 실행한다")
	void enabledRunnerCleansUpWithRetentionCutoff() {
		NewsArticleObservationCleanupService service = mock(NewsArticleObservationCleanupService.class);
		when(service.cleanup(OffsetDateTime.parse("2026-06-05T00:00:00Z")))
			.thenReturn(new NewsArticleObservationCleanupResult(0, java.util.List.of()));
		NewsArticleObservationCleanupApplicationRunner runner = new NewsArticleObservationCleanupApplicationRunner(
			service,
			new NewsArticleObservationCleanupProperties(true, Duration.ofDays(2)),
			FIXED_CLOCK
		);

		runner.run(null);

		verify(service).cleanup(OffsetDateTime.parse("2026-06-05T00:00:00Z"));
		assertThat(runner.getOrder()).isEqualTo(ApplicationRunnerOrders.NEWS_OBSERVATION_CLEANUP);
		assertThat(runner.getOrder()).isGreaterThan(ApplicationRunnerOrders.NEWS_SIGNAL_FEATURE_EXTRACTION);
	}
}
