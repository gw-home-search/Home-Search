package com.home.infrastructure.external.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.home.application.news.observation.NewsArticleObservationIngestResult;
import com.home.application.news.relevance.NewsArticleRelevanceGateResult;
import com.home.application.news.relevance.NewsArticleRelevanceGateService;
import com.home.application.news.signal.NewsSignalFeatureExtractionResult;
import com.home.application.news.signal.NewsSignalFeatureExtractionService;
import com.home.application.news.export.NewsSignalObsidianExportCommand;
import com.home.application.news.export.NewsSignalObsidianExportResult;
import com.home.application.news.export.NewsSignalObsidianExportService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class NaverNewsSignalPipelineApplicationRunnerTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-07T12:00:00Z"), ZoneId.of("UTC"));

	@Test
	@DisplayName("Naver News signal pipeline runner는 disabled 설정이면 어떤 단계도 실행하지 않는다")
	void disabledPipelineDoesNotRunSteps() {
		NaverNewsOneShotIngestRunner ingestRunner = mock(NaverNewsOneShotIngestRunner.class);
		NewsArticleRelevanceGateService relevanceGateService = mock(NewsArticleRelevanceGateService.class);
		NewsSignalFeatureExtractionService featureExtractionService = mock(NewsSignalFeatureExtractionService.class);
		NewsSignalObsidianExportService exportService = mock(NewsSignalObsidianExportService.class);
		NaverNewsSignalPipelineApplicationRunner runner = new NaverNewsSignalPipelineApplicationRunner(
			ingestRunner,
			relevanceGateService,
			featureExtractionService,
			exportService,
			pipelineProperties(false),
			new NaverNewsOneShotIngestProperties(false, "", 100, 1, "date", false),
			searchProperties(),
			CLOCK
		);

		runner.run(null);

		assertThat(runner.getOrder()).isEqualTo(ApplicationRunnerOrders.NEWS_SIGNAL_PIPELINE);
		verifyNoInteractions(ingestRunner, relevanceGateService, featureExtractionService, exportService);
	}

	@Test
	@DisplayName("Naver News signal pipeline runner는 ingest부터 Obsidian export까지 순서대로 실행한다")
	void enabledPipelineRunsStepsInOrder() {
		NaverNewsOneShotIngestRunner ingestRunner = mock(NaverNewsOneShotIngestRunner.class);
		NewsArticleRelevanceGateService relevanceGateService = mock(NewsArticleRelevanceGateService.class);
		NewsSignalFeatureExtractionService featureExtractionService = mock(NewsSignalFeatureExtractionService.class);
		NewsSignalObsidianExportService exportService = mock(NewsSignalObsidianExportService.class);
		when(ingestRunner.ingest(any()))
			.thenReturn(new NewsArticleObservationIngestResult(4, 3, 1));
		when(relevanceGateService.evaluateObserved(40))
			.thenReturn(new NewsArticleRelevanceGateResult(3, 2, 1, 0, 0, 0));
		when(featureExtractionService.extractPending(30))
			.thenReturn(new NewsSignalFeatureExtractionResult(2, 2, 2, 0));
		when(exportService.exportDaily(any()))
			.thenReturn(new NewsSignalObsidianExportResult(
				LocalDate.parse("2026-06-07"),
				Path.of("/tmp/obsidian/news-signals/daily/2026-06-07.md"),
				2,
				2,
				false
			));
		NaverNewsSignalPipelineApplicationRunner runner = new NaverNewsSignalPipelineApplicationRunner(
			ingestRunner,
			relevanceGateService,
			featureExtractionService,
			exportService,
			pipelineProperties(true),
			new NaverNewsOneShotIngestProperties(true, "강남 재건축", 10, 2, "date", false),
			searchProperties(),
			CLOCK
		);

		runner.run(null);

		InOrder ordered = inOrder(ingestRunner, relevanceGateService, featureExtractionService, exportService);
		ordered.verify(ingestRunner).ingest(new NaverNewsSearchRequest("강남 재건축", 10, 2, "date"));
		ordered.verify(relevanceGateService).evaluateObserved(40);
		ordered.verify(featureExtractionService).extractPending(30);
		ArgumentCaptor<NewsSignalObsidianExportCommand> commandCaptor =
			ArgumentCaptor.forClass(NewsSignalObsidianExportCommand.class);
		ordered.verify(exportService).exportDaily(commandCaptor.capture());
		assertThat(commandCaptor.getValue().outputRoot()).isEqualTo(Path.of("/tmp/obsidian"));
		assertThat(commandCaptor.getValue().date()).isEqualTo(LocalDate.parse("2026-06-07"));
		assertThat(commandCaptor.getValue().maxRows()).isEqualTo(200);
	}

	@Test
	@DisplayName("Naver News signal pipeline runner는 Obsidian export가 truncated이면 완료로 처리하지 않는다")
	void truncatedObsidianExportFailsPipeline() {
		NaverNewsOneShotIngestRunner ingestRunner = mock(NaverNewsOneShotIngestRunner.class);
		NewsArticleRelevanceGateService relevanceGateService = mock(NewsArticleRelevanceGateService.class);
		NewsSignalFeatureExtractionService featureExtractionService = mock(NewsSignalFeatureExtractionService.class);
		NewsSignalObsidianExportService exportService = mock(NewsSignalObsidianExportService.class);
		when(ingestRunner.ingest(any()))
			.thenReturn(new NewsArticleObservationIngestResult(3, 3, 0));
		when(relevanceGateService.evaluateObserved(40))
			.thenReturn(new NewsArticleRelevanceGateResult(3, 3, 0, 0, 0, 0));
		when(featureExtractionService.extractPending(30))
			.thenReturn(new NewsSignalFeatureExtractionResult(3, 3, 3, 0));
		when(exportService.exportDaily(any()))
			.thenReturn(new NewsSignalObsidianExportResult(
				LocalDate.parse("2026-06-07"),
				Path.of("/tmp/obsidian/news-signals/daily/2026-06-07.md"),
				1,
				1,
				true
			));
		NaverNewsSignalPipelineApplicationRunner runner = new NaverNewsSignalPipelineApplicationRunner(
			ingestRunner,
			relevanceGateService,
			featureExtractionService,
			exportService,
			pipelineProperties(true),
			new NaverNewsOneShotIngestProperties(true, "강남 재건축", 10, 2, "date", false),
			searchProperties(),
			CLOCK
		);

		assertThatThrownBy(() -> runner.run(null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("truncated");
	}

	@Test
	@DisplayName("Naver News signal pipeline runner는 preflight 설정이면 인증과 request만 검증하고 단계를 실행하지 않는다")
	void preflightPipelineDoesNotRunSteps() {
		NaverNewsOneShotIngestRunner ingestRunner = mock(NaverNewsOneShotIngestRunner.class);
		NewsArticleRelevanceGateService relevanceGateService = mock(NewsArticleRelevanceGateService.class);
		NewsSignalFeatureExtractionService featureExtractionService = mock(NewsSignalFeatureExtractionService.class);
		NewsSignalObsidianExportService exportService = mock(NewsSignalObsidianExportService.class);
		NaverNewsSignalPipelineApplicationRunner runner = new NaverNewsSignalPipelineApplicationRunner(
			ingestRunner,
			relevanceGateService,
			featureExtractionService,
			exportService,
			pipelineProperties(true),
			new NaverNewsOneShotIngestProperties(true, "강남 재건축", 10, 2, "date", true),
			searchProperties(),
			CLOCK
		);

		runner.run(null);

		verifyNoInteractions(ingestRunner, relevanceGateService, featureExtractionService, exportService);
	}

	private static NaverNewsSignalPipelineProperties pipelineProperties(boolean enabled) {
		return new NaverNewsSignalPipelineProperties(
			enabled,
			40,
			30,
			Path.of("/tmp/obsidian"),
			LocalDate.parse("2026-06-07"),
			ZoneId.of("Asia/Seoul"),
			200
		);
	}

	private static NaverNewsSearchProperties searchProperties() {
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
