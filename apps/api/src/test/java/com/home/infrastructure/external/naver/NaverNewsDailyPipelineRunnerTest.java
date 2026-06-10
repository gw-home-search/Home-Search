package com.home.infrastructure.external.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.home.application.news.collection.NewsCollectionArticleDiscovery;
import com.home.application.news.collection.NewsCollectionKeyword;
import com.home.application.news.collection.NewsCollectionRepository;
import com.home.application.news.collection.NewsCollectionRunCompletion;
import com.home.application.news.collection.NewsCollectionRunKeywordCompletion;
import com.home.application.news.export.NewsSignalObsidianExportCommand;
import com.home.application.news.export.NewsSignalObsidianExportResult;
import com.home.application.news.export.NewsSignalObsidianExportService;
import com.home.application.news.observation.NewsArticleObservationCommand;
import com.home.application.news.observation.NewsArticleObservationDetailedIngestResult;
import com.home.application.news.observation.NewsArticleObservationIngestResult;
import com.home.application.news.observation.NewsArticleObservationStatus;
import com.home.application.news.relevance.NewsArticleRelevanceGateResult;
import com.home.application.news.relevance.NewsArticleRelevanceGateService;
import com.home.application.news.signal.NewsSignalFeatureExtractionResult;
import com.home.application.news.signal.NewsSignalFeatureExtractionService;
import com.home.domain.news.NewsCollectionArticleDisposition;
import com.home.domain.news.NewsCollectionKeywordCadence;
import com.home.domain.news.NewsCollectionKeywordType;
import com.home.domain.news.NewsCollectionNotificationStatus;
import com.home.domain.news.NewsCollectionRunStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NaverNewsDailyPipelineRunnerTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-09T19:00:00Z"), ZoneId.of("UTC"));

	@Test
	@DisplayName("Naver News daily pipeline은 due keyword별 수집 provenance와 후속 처리 결과를 run history로 남긴다")
	void dailyPipelineRecordsKeywordArticleProvenanceAndPostProcessing() {
		RecordingNewsCollectionRepository repository = new RecordingNewsCollectionRepository(List.of(
			keyword(1L, "강남 재건축", 90),
			keyword(2L, "분양가 상한제", 80)
		));
		NaverNewsOneShotIngestRunner ingestRunner = mock(NaverNewsOneShotIngestRunner.class);
		NewsArticleRelevanceGateService relevanceGateService = mock(NewsArticleRelevanceGateService.class);
		NewsSignalFeatureExtractionService featureExtractionService = mock(NewsSignalFeatureExtractionService.class);
		NewsSignalObsidianExportService exportService = mock(NewsSignalObsidianExportService.class);
		NaverNewsDailyPipelineNotifier notifier = mock(NaverNewsDailyPipelineNotifier.class);
		when(notifier.requested()).thenReturn(true);
		when(ingestRunner.ingestDetailed(new NaverNewsSearchRequest("강남 재건축", 10, 1, "date")))
			.thenReturn(outcome("NAVER_NEWS:gangnam-1", true));
		when(ingestRunner.ingestDetailed(new NaverNewsSearchRequest("분양가 상한제", 10, 1, "date")))
			.thenReturn(outcome("NAVER_NEWS:price-cap-1", false));
		when(relevanceGateService.evaluateObserved(40))
			.thenReturn(new NewsArticleRelevanceGateResult(2, 1, 0, 1, 1, 0));
		when(featureExtractionService.extractPending(30))
			.thenReturn(new NewsSignalFeatureExtractionResult(1, 1, 1, 0));
		when(exportService.exportDaily(any()))
			.thenReturn(new NewsSignalObsidianExportResult(
				LocalDate.parse("2026-06-10"),
				Path.of("/tmp/obsidian/news-signals/daily/2026-06-10.md"),
				1,
				1,
				false
			));
		NaverNewsDailyPipelineRunner runner = new NaverNewsDailyPipelineRunner(
			repository,
			ingestRunner,
			relevanceGateService,
			featureExtractionService,
			exportService,
			notifier,
			new NaverNewsDailyPipelineMessageFormatter(),
			properties(),
			CLOCK
		);

		NaverNewsDailyPipelineExecution execution = runner.runOnce();

		assertThat(execution.status()).isEqualTo(NewsCollectionRunStatus.COMPLETED);
		assertThat(repository.startedKeywordQueries()).containsExactly("강남 재건축", "분양가 상한제");
		assertThat(repository.articleDiscoveries())
			.extracting(NewsCollectionArticleDiscovery::sourceKey)
			.containsExactly("NAVER_NEWS:gangnam-1", "NAVER_NEWS:price-cap-1");
		assertThat(repository.articleDiscoveries())
			.extracting(NewsCollectionArticleDiscovery::disposition)
			.containsExactly(NewsCollectionArticleDisposition.OBSERVED, NewsCollectionArticleDisposition.DUPLICATE);
		assertThat(repository.keywordCompletions())
			.extracting(NewsCollectionRunKeywordCompletion::status)
			.containsExactly(NewsCollectionRunStatus.COMPLETED, NewsCollectionRunStatus.COMPLETED);
		assertThat(repository.runCompletion().status()).isEqualTo(NewsCollectionRunStatus.COMPLETED);
		assertThat(repository.runCompletion().notificationStatus()).isEqualTo(NewsCollectionNotificationStatus.SENT);
		assertThat(repository.runCompletion().readCount()).isEqualTo(2);
		assertThat(repository.runCompletion().observedCount()).isEqualTo(1);
		assertThat(repository.runCompletion().duplicateSkippedCount()).isEqualTo(1);
		assertThat(repository.runCompletion().relevanceKeptCount()).isEqualTo(1);
		assertThat(repository.runCompletion().extractedCount()).isEqualTo(1);
		assertThat(repository.markedCollectedKeywordIds()).containsExactly(1L, 2L);
		verify(notifier).send(any());
	}

	@Test
	@DisplayName("Naver News daily pipeline은 keyword 실패를 PARTIAL로 기록하고 다음 keyword를 계속 처리한다")
	void dailyPipelineKeepsProcessingAfterKeywordFailure() {
		RecordingNewsCollectionRepository repository = new RecordingNewsCollectionRepository(List.of(
			keyword(1L, "강남 재건축", 90),
			keyword(2L, "분양가 상한제", 80)
		));
		NaverNewsOneShotIngestRunner ingestRunner = mock(NaverNewsOneShotIngestRunner.class);
		NewsArticleRelevanceGateService relevanceGateService = mock(NewsArticleRelevanceGateService.class);
		NewsSignalFeatureExtractionService featureExtractionService = mock(NewsSignalFeatureExtractionService.class);
		NewsSignalObsidianExportService exportService = mock(NewsSignalObsidianExportService.class);
		NaverNewsDailyPipelineNotifier notifier = mock(NaverNewsDailyPipelineNotifier.class);
		when(notifier.requested()).thenReturn(true);
		when(ingestRunner.ingestDetailed(new NaverNewsSearchRequest("강남 재건축", 10, 1, "date")))
			.thenThrow(new IllegalStateException("Naver quota exceeded sourceKey=PRIVATE_VALUE"));
		when(ingestRunner.ingestDetailed(new NaverNewsSearchRequest("분양가 상한제", 10, 1, "date")))
			.thenReturn(outcome("NAVER_NEWS:price-cap-1", true));
		when(relevanceGateService.evaluateObserved(40)).thenReturn(NewsArticleRelevanceGateResult.empty());
		when(featureExtractionService.extractPending(30)).thenReturn(NewsSignalFeatureExtractionResult.empty());
		when(exportService.exportDaily(any())).thenReturn(new NewsSignalObsidianExportResult(
			LocalDate.parse("2026-06-10"),
			Path.of("/tmp/obsidian/news-signals/daily/2026-06-10.md"),
			0,
			0,
			false
		));
		NaverNewsDailyPipelineRunner runner = new NaverNewsDailyPipelineRunner(
			repository,
			ingestRunner,
			relevanceGateService,
			featureExtractionService,
			exportService,
			notifier,
			new NaverNewsDailyPipelineMessageFormatter(),
			properties(),
			CLOCK
		);

		NaverNewsDailyPipelineExecution execution = runner.runOnce();

		assertThat(execution.status()).isEqualTo(NewsCollectionRunStatus.PARTIAL);
		assertThat(repository.keywordCompletions())
			.extracting(NewsCollectionRunKeywordCompletion::status)
			.containsExactly(NewsCollectionRunStatus.FAILED, NewsCollectionRunStatus.COMPLETED);
		assertThat(repository.markedCollectedKeywordIds()).containsExactly(2L);
		assertThat(repository.runCompletion().failureReason()).contains("IllegalStateException");
		assertThat(repository.runCompletion().failureReason()).doesNotContain("PRIVATE_VALUE");
	}

	@Test
	@DisplayName("Naver News daily pipeline은 Hermes 실패를 run 실패로 뒤집지 않고 notification 실패만 저장한다")
	void dailyPipelineRecordsNotificationFailureWithoutFailingRun() {
		RecordingNewsCollectionRepository repository = new RecordingNewsCollectionRepository(List.of(
			keyword(1L, "강남 재건축", 90)
		));
		NaverNewsOneShotIngestRunner ingestRunner = mock(NaverNewsOneShotIngestRunner.class);
		NewsArticleRelevanceGateService relevanceGateService = mock(NewsArticleRelevanceGateService.class);
		NewsSignalFeatureExtractionService featureExtractionService = mock(NewsSignalFeatureExtractionService.class);
		NewsSignalObsidianExportService exportService = mock(NewsSignalObsidianExportService.class);
		NaverNewsDailyPipelineNotifier notifier = message -> {
			throw new IllegalStateException("Hermes failed sourceKey=PRIVATE_VALUE");
		};
		when(ingestRunner.ingestDetailed(any())).thenReturn(outcome("NAVER_NEWS:gangnam-1", true));
		when(relevanceGateService.evaluateObserved(40)).thenReturn(NewsArticleRelevanceGateResult.empty());
		when(featureExtractionService.extractPending(30)).thenReturn(NewsSignalFeatureExtractionResult.empty());
		when(exportService.exportDaily(any())).thenReturn(new NewsSignalObsidianExportResult(
			LocalDate.parse("2026-06-10"),
			Path.of("/tmp/obsidian/news-signals/daily/2026-06-10.md"),
			0,
			0,
			false
		));
		NaverNewsDailyPipelineRunner runner = new NaverNewsDailyPipelineRunner(
			repository,
			ingestRunner,
			relevanceGateService,
			featureExtractionService,
			exportService,
			notifier,
			new NaverNewsDailyPipelineMessageFormatter(),
			properties(),
			CLOCK
		);

		NaverNewsDailyPipelineExecution execution = runner.runOnce();

		assertThat(execution.status()).isEqualTo(NewsCollectionRunStatus.COMPLETED);
		assertThat(repository.runCompletion().notificationStatus()).isEqualTo(NewsCollectionNotificationStatus.FAILED);
		assertThat(repository.runCompletion().notificationFailureReason()).doesNotContain("PRIVATE_VALUE");
	}

	@Test
	@DisplayName("Naver News daily pipeline은 Hermes 비활성 상태를 알림 미요청으로 기록한다")
	void dailyPipelineRecordsNotificationNotRequestedWhenNotifierIsNoop() {
		RecordingNewsCollectionRepository repository = new RecordingNewsCollectionRepository(List.of(
			keyword(1L, "강남 재건축", 90)
		));
		NaverNewsOneShotIngestRunner ingestRunner = mock(NaverNewsOneShotIngestRunner.class);
		NewsArticleRelevanceGateService relevanceGateService = mock(NewsArticleRelevanceGateService.class);
		NewsSignalFeatureExtractionService featureExtractionService = mock(NewsSignalFeatureExtractionService.class);
		NewsSignalObsidianExportService exportService = mock(NewsSignalObsidianExportService.class);
		when(ingestRunner.ingestDetailed(any())).thenReturn(outcome("NAVER_NEWS:gangnam-1", true));
		when(relevanceGateService.evaluateObserved(40)).thenReturn(NewsArticleRelevanceGateResult.empty());
		when(featureExtractionService.extractPending(30)).thenReturn(NewsSignalFeatureExtractionResult.empty());
		when(exportService.exportDaily(any())).thenReturn(new NewsSignalObsidianExportResult(
			LocalDate.parse("2026-06-10"),
			Path.of("/tmp/obsidian/news-signals/daily/2026-06-10.md"),
			0,
			0,
			false
		));
		NaverNewsDailyPipelineRunner runner = new NaverNewsDailyPipelineRunner(
			repository,
			ingestRunner,
			relevanceGateService,
			featureExtractionService,
			exportService,
			NaverNewsDailyPipelineNotifier.noop(),
			new NaverNewsDailyPipelineMessageFormatter(),
			properties(),
			CLOCK
		);

		NaverNewsDailyPipelineExecution execution = runner.runOnce();

		assertThat(execution.status()).isEqualTo(NewsCollectionRunStatus.COMPLETED);
		assertThat(repository.runCompletion().notificationStatus())
			.isEqualTo(NewsCollectionNotificationStatus.NOT_REQUESTED);
	}

	private static NaverNewsDailyPipelineProperties properties() {
		return new NaverNewsDailyPipelineProperties(
			true,
			10,
			10,
			"date",
			40,
			30,
			Path.of("/tmp/obsidian"),
			null,
			ZoneId.of("Asia/Seoul"),
			200
		);
	}

	private static NewsCollectionKeyword keyword(long id, String query, int priority) {
		return new NewsCollectionKeyword(
			id,
			query,
			NewsCollectionKeywordType.TOPIC,
			null,
			null,
			priority,
			NewsCollectionKeywordCadence.DAILY
		);
	}

	private static NaverNewsOneShotIngestOutcome outcome(String sourceKey, boolean observed) {
		return new NaverNewsOneShotIngestOutcome(
			new NewsArticleObservationDetailedIngestResult(
				new NewsArticleObservationIngestResult(1, observed ? 1 : 0, observed ? 0 : 1),
				List.of(new NewsArticleObservationDetailedIngestResult.ArticleOutcome(command(sourceKey), observed))
			)
		);
	}

	private static NewsArticleObservationCommand command(String sourceKey) {
		return new NewsArticleObservationCommand(
			"NAVER_NEWS",
			sourceKey,
			"example.com",
			"강남 재건축",
			"https://example.com/news/1",
			"https://n.news.naver.com/mnews/article/001/0000000001",
			"서울 강남 재건축",
			OffsetDateTime.parse("2026-06-10T03:30:00+09:00"),
			OffsetDateTime.parse("2026-06-10T03:30:00+09:00"),
			OffsetDateTime.parse("2026-06-09T19:00:00Z"),
			OffsetDateTime.parse("2026-06-09T19:00:00Z"),
			null,
			LocalDate.parse("2026-06-10"),
			"{\"title\":\"강남 재건축\"}",
			"payload-hash",
			NewsArticleObservationStatus.OBSERVED,
			null
		);
	}

	private static final class RecordingNewsCollectionRepository implements NewsCollectionRepository {

		private final List<NewsCollectionKeyword> dueKeywords;
		private final List<String> startedKeywordQueries = new ArrayList<>();
		private final List<NewsCollectionArticleDiscovery> articleDiscoveries = new ArrayList<>();
		private final List<NewsCollectionRunKeywordCompletion> keywordCompletions = new ArrayList<>();
		private final List<Long> markedCollectedKeywordIds = new ArrayList<>();
		private NewsCollectionRunCompletion runCompletion;
		private long nextId = 1;

		RecordingNewsCollectionRepository(List<NewsCollectionKeyword> dueKeywords) {
			this.dueKeywords = dueKeywords;
		}

		@Override
		public List<NewsCollectionKeyword> findDueKeywords(OffsetDateTime now, int limit) {
			return dueKeywords.stream().limit(limit).toList();
		}

		@Override
		public long startRun(OffsetDateTime startedAt) {
			return nextId++;
		}

		@Override
		public long startKeyword(long runId, NewsCollectionKeyword keyword, OffsetDateTime startedAt) {
			startedKeywordQueries.add(keyword.queryText());
			return nextId++;
		}

		@Override
		public void recordArticles(long runKeywordId, List<NewsCollectionArticleDiscovery> discoveries) {
			articleDiscoveries.addAll(discoveries);
		}

		@Override
		public void completeKeyword(NewsCollectionRunKeywordCompletion completion) {
			keywordCompletions.add(completion);
		}

		@Override
		public void markKeywordCollected(long keywordId, OffsetDateTime collectedAt, OffsetDateTime nextDueAt) {
			markedCollectedKeywordIds.add(keywordId);
		}

		@Override
		public void completeRun(NewsCollectionRunCompletion completion) {
			runCompletion = completion;
		}

		List<String> startedKeywordQueries() {
			return startedKeywordQueries;
		}

		List<NewsCollectionArticleDiscovery> articleDiscoveries() {
			return articleDiscoveries;
		}

		List<NewsCollectionRunKeywordCompletion> keywordCompletions() {
			return keywordCompletions;
		}

		List<Long> markedCollectedKeywordIds() {
			return markedCollectedKeywordIds;
		}

		NewsCollectionRunCompletion runCompletion() {
			return runCompletion;
		}
	}
}
