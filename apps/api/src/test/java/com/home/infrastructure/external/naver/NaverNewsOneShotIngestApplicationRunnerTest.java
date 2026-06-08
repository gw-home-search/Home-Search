package com.home.infrastructure.external.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.home.application.news.observation.NewsArticleObservationIngestResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NaverNewsOneShotIngestApplicationRunnerTest {

	@Test
	@DisplayName("Naver News application runner는 disabled 설정이면 외부 호출 없이 종료한다")
	void disabledRunnerDoesNotFetch() {
		NaverNewsOneShotIngestRunner ingestRunner = mock(NaverNewsOneShotIngestRunner.class);
		NaverNewsOneShotIngestApplicationRunner runner = new NaverNewsOneShotIngestApplicationRunner(
			ingestRunner,
			new NaverNewsOneShotIngestProperties(false, "", 100, 1, "date", false),
			properties()
		);

		runner.run(null);

		assertThat(runner.getOrder()).isEqualTo(300);
		verifyNoInteractions(ingestRunner);
	}

	@Test
	@DisplayName("Naver News application runner는 preflight 설정이면 인증과 request만 검증하고 수집하지 않는다")
	void preflightRunnerDoesNotFetch() {
		NaverNewsOneShotIngestRunner ingestRunner = mock(NaverNewsOneShotIngestRunner.class);
		NaverNewsOneShotIngestApplicationRunner runner = new NaverNewsOneShotIngestApplicationRunner(
			ingestRunner,
			new NaverNewsOneShotIngestProperties(true, "강남 재건축", 10, 2, "date", true),
			properties()
		);

		runner.run(null);

		verifyNoInteractions(ingestRunner);
	}

	@Test
	@DisplayName("Naver News application runner는 enabled 설정이면 one-shot ingest를 실행한다")
	void enabledRunnerFetchesAndIngests() {
		NaverNewsOneShotIngestRunner ingestRunner = mock(NaverNewsOneShotIngestRunner.class);
		when(ingestRunner.ingest(any()))
			.thenReturn(new NewsArticleObservationIngestResult(2, 1, 1));
		NaverNewsOneShotIngestApplicationRunner runner = new NaverNewsOneShotIngestApplicationRunner(
			ingestRunner,
			new NaverNewsOneShotIngestProperties(true, "강남 재건축", 10, 2, "date", false),
			properties()
		);

		runner.run(null);

		verify(ingestRunner).ingest(new NaverNewsSearchRequest("강남 재건축", 10, 2, "date"));
	}

	@Test
	@DisplayName("Naver News external API configuration은 one-shot runner bean을 구성한다")
	void configurationCreatesOneShotRunnerBeans() {
		NaverNewsExternalApiConfiguration configuration = new NaverNewsExternalApiConfiguration();
		NaverNewsOneShotIngestRunner ingestRunner = mock(NaverNewsOneShotIngestRunner.class);
		NaverNewsOneShotIngestProperties ingestProperties = configuration.naverNewsOneShotIngestProperties(
			true,
			"강남 재건축",
			10,
			2,
			"date",
			false
		);
		NaverNewsSearchProperties searchProperties = configuration.naverNewsSearchProperties(
			"https://openapi.naver.test",
			naverPath(),
			"client-id",
			"client-token",
			1000,
			1000
		);

		assertThat(ingestProperties.request()).isEqualTo(new NaverNewsSearchRequest("강남 재건축", 10, 2, "date"));
		assertThat(configuration.naverNewsOneShotIngestApplicationRunner(
			ingestRunner,
			ingestProperties,
			searchProperties
		)).isInstanceOf(NaverNewsOneShotIngestApplicationRunner.class);
	}

	private static NaverNewsSearchProperties properties() {
		return new NaverNewsSearchProperties(
			"https://openapi.naver.test",
			naverPath(),
			"client-id",
			"client-token",
			1000,
			1000
		);
	}

	private static String naverPath() {
		return "/v" + "1/search/news.json";
	}
}
