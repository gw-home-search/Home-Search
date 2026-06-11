package com.home.infrastructure.scheduling.news;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.home.application.news.collection.NewsCollectionArticleDiscovery;
import com.home.application.news.collection.NewsCollectionKeyword;
import com.home.application.news.collection.NewsCollectionRepository;
import com.home.application.news.collection.NewsCollectionRunCompletion;
import com.home.application.news.collection.NewsCollectionRunKeywordCompletion;
import com.home.application.news.export.NewsSignalObsidianExportCommand;
import com.home.application.news.export.NewsSignalObsidianExportResult;
import com.home.application.news.export.NewsSignalObsidianExportService;
import com.home.application.news.observation.NewsArticleObservationDetailedIngestResult;
import com.home.application.news.observation.NewsArticleObservationIngestResult;
import com.home.application.news.relevance.NewsArticleRelevanceGateResult;
import com.home.application.news.relevance.NewsArticleRelevanceGateService;
import com.home.application.news.signal.NewsSignalFeatureExtractionResult;
import com.home.application.news.signal.NewsSignalFeatureExtractionService;
import com.home.domain.news.NewsCollectionArticleDisposition;
import com.home.domain.news.NewsCollectionNotificationStatus;
import com.home.domain.news.NewsCollectionRunStatus;
import com.home.infrastructure.external.naver.NaverNewsOneShotIngestOutcome;
import com.home.infrastructure.external.naver.NaverNewsOneShotIngestRunner;
import com.home.infrastructure.external.naver.NaverNewsSearchRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NaverNewsDailyPipelineRunner {

	private static final Logger log = LoggerFactory.getLogger(NaverNewsDailyPipelineRunner.class);

	private final NewsCollectionRepository collectionRepository;
	private final NaverNewsOneShotIngestRunner ingestRunner;
	private final NewsArticleRelevanceGateService relevanceGateService;
	private final NewsSignalFeatureExtractionService featureExtractionService;
	private final NewsSignalObsidianExportService obsidianExportService;
	private final NaverNewsDailyPipelineNotifier notifier;
	private final NaverNewsDailyPipelineMessageFormatter formatter;
	private final NaverNewsDailyPipelineProperties properties;
	private final Clock clock;

	NaverNewsDailyPipelineRunner(
		NewsCollectionRepository collectionRepository,
		NaverNewsOneShotIngestRunner ingestRunner,
		NewsArticleRelevanceGateService relevanceGateService,
		NewsSignalFeatureExtractionService featureExtractionService,
		NewsSignalObsidianExportService obsidianExportService,
		NaverNewsDailyPipelineNotifier notifier,
		NaverNewsDailyPipelineMessageFormatter formatter,
		NaverNewsDailyPipelineProperties properties,
		Clock clock
	) {
		this.collectionRepository = Objects.requireNonNull(collectionRepository, "collectionRepository must not be null");
		this.ingestRunner = Objects.requireNonNull(ingestRunner, "ingestRunner must not be null");
		this.relevanceGateService = Objects.requireNonNull(relevanceGateService, "relevanceGateService must not be null");
		this.featureExtractionService = Objects.requireNonNull(
			featureExtractionService,
			"featureExtractionService must not be null"
		);
		this.obsidianExportService = Objects.requireNonNull(obsidianExportService, "obsidianExportService must not be null");
		this.notifier = Objects.requireNonNull(notifier, "notifier must not be null");
		this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	NaverNewsDailyPipelineExecution runOnce() {
		OffsetDateTime startedAt = OffsetDateTime.now(clock);
		long runId = collectionRepository.startRun(startedAt);
		List<NewsCollectionKeyword> keywords = collectionRepository.findDueKeywords(startedAt, properties.maxKeywords());
		List<NaverNewsDailyPipelineKeywordExecution> keywordExecutions = runKeywords(runId, keywords);
		PostProcessingResult postProcessing = runPostProcessing(keywords, keywordExecutions);
		NewsCollectionRunStatus status = status(keywords, keywordExecutions, postProcessing);
		OffsetDateTime finishedAt = OffsetDateTime.now(clock);
		NewsCollectionRunCompletion preNotificationCompletion = completion(
			runId,
			status,
			finishedAt,
			keywords.size(),
			keywordExecutions,
			postProcessing,
			NewsCollectionNotificationStatus.NOT_REQUESTED,
			null
		);
		NotificationResult notification = notify(new NaverNewsDailyPipelineExecution(
			runId,
			status,
			keywordExecutions,
			preNotificationCompletion
		));
		NewsCollectionRunCompletion completion = completion(
			runId,
			status,
			finishedAt,
			keywords.size(),
			keywordExecutions,
			postProcessing,
			notification.status(),
			notification.failureReason()
		);
		collectionRepository.completeRun(completion);
		log.info(
			"Naver News daily pipeline completed runId={} status={} keywords={} read={} observed={} duplicateSkipped={} relevanceKept={} extracted={} notificationStatus={}",
			runId,
			completion.status(),
			completion.keywordCount(),
			completion.readCount(),
			completion.observedCount(),
			completion.duplicateSkippedCount(),
			completion.relevanceKeptCount(),
			completion.extractedCount(),
			completion.notificationStatus()
		);
		return new NaverNewsDailyPipelineExecution(runId, status, keywordExecutions, completion);
	}

	private List<NaverNewsDailyPipelineKeywordExecution> runKeywords(long runId, List<NewsCollectionKeyword> keywords) {
		List<NaverNewsDailyPipelineKeywordExecution> executions = new ArrayList<>();
		for (NewsCollectionKeyword keyword : keywords) {
			long runKeywordId = collectionRepository.startKeyword(runId, keyword, OffsetDateTime.now(clock));
			try {
				NaverNewsOneShotIngestOutcome outcome = ingestRunner.ingestDetailed(
					new NaverNewsSearchRequest(keyword.queryText(), properties.display(), 1, properties.sort())
				);
				NewsArticleObservationDetailedIngestResult detailedResult = outcome.detailedResult();
				collectionRepository.recordArticles(runKeywordId, discoveries(detailedResult));
				NewsArticleObservationIngestResult ingestResult = detailedResult.result();
				OffsetDateTime finishedAt = OffsetDateTime.now(clock);
				collectionRepository.completeKeyword(new NewsCollectionRunKeywordCompletion(
					runKeywordId,
					NewsCollectionRunStatus.COMPLETED,
					finishedAt,
					ingestResult.read(),
					ingestResult.observed(),
					ingestResult.duplicateSkipped(),
					null
				));
				collectionRepository.markKeywordCollected(keyword.id(), finishedAt, keyword.cadence().nextDueAfter(finishedAt));
				executions.add(NaverNewsDailyPipelineKeywordExecution.completed(keyword, ingestResult));
			}
			catch (RuntimeException exception) {
				NaverNewsDailyPipelineKeywordExecution failed = NaverNewsDailyPipelineKeywordExecution.failed(
					keyword,
					exception
				);
				collectionRepository.completeKeyword(new NewsCollectionRunKeywordCompletion(
					runKeywordId,
					NewsCollectionRunStatus.FAILED,
					OffsetDateTime.now(clock),
					0,
					0,
					0,
					failed.failureReason()
				));
				executions.add(failed);
			}
		}
		return executions;
	}

	private PostProcessingResult runPostProcessing(
		List<NewsCollectionKeyword> keywords,
		List<NaverNewsDailyPipelineKeywordExecution> keywordExecutions
	) {
		if (keywords.isEmpty()) {
			return PostProcessingResult.empty();
		}
		if (keywordExecutions.stream().noneMatch(execution -> execution.status().isCompleted())) {
			return PostProcessingResult.failed("All keyword collections failed");
		}
		try {
			NewsArticleRelevanceGateResult relevanceResult =
				relevanceGateService.evaluateObserved(properties.relevanceLimit());
			NewsSignalFeatureExtractionResult extractionResult =
				featureExtractionService.extractPending(properties.featureExtractionLimit());
			LocalDate exportDate = properties.obsidianDate() == null
				? LocalDate.now(clock.withZone(properties.obsidianZoneId()))
				: properties.obsidianDate();
			NewsSignalObsidianExportResult exportResult = obsidianExportService.exportDaily(
				new NewsSignalObsidianExportCommand(
					properties.obsidianOutputRoot(),
					exportDate,
					properties.obsidianZoneId(),
					properties.obsidianMaxRows()
				)
			);
			if (exportResult.truncated()) {
				return PostProcessingResult.failed(
					"News signal daily export was truncated: path=%s maxRows=%d features=%d"
						.formatted(exportResult.path(), properties.obsidianMaxRows(), exportResult.featureCount()),
					relevanceResult,
					extractionResult,
					exportResult
				);
			}
			return new PostProcessingResult(relevanceResult, extractionResult, exportResult, null);
		}
		catch (RuntimeException exception) {
			return PostProcessingResult.failed(failureReason(exception));
		}
	}

	private NotificationResult notify(NaverNewsDailyPipelineExecution execution) {
		if (!notifier.requested()) {
			return new NotificationResult(NewsCollectionNotificationStatus.NOT_REQUESTED, null);
		}
		try {
			notifier.send(formatter.format(execution));
			return new NotificationResult(NewsCollectionNotificationStatus.SENT, null);
		}
		catch (RuntimeException exception) {
			String reason = sanitize(failureReason(exception));
			log.warn("Naver News daily pipeline Hermes notification failed reason={}", reason);
			return new NotificationResult(NewsCollectionNotificationStatus.FAILED, reason);
		}
	}

	private static List<NewsCollectionArticleDiscovery> discoveries(NewsArticleObservationDetailedIngestResult result) {
		List<NewsCollectionArticleDiscovery> discoveries = new ArrayList<>();
		int rank = 1;
		for (NewsArticleObservationDetailedIngestResult.ArticleOutcome outcome : result.articleOutcomes()) {
			discoveries.add(new NewsCollectionArticleDiscovery(
				outcome.command().source(),
				outcome.command().sourceKey(),
				rank,
				outcome.observed()
					? NewsCollectionArticleDisposition.OBSERVED
					: NewsCollectionArticleDisposition.DUPLICATE
			));
			rank++;
		}
		return discoveries;
	}

	private static NewsCollectionRunStatus status(
		List<NewsCollectionKeyword> keywords,
		List<NaverNewsDailyPipelineKeywordExecution> keywordExecutions,
		PostProcessingResult postProcessing
	) {
		if (keywords.isEmpty()) {
			return NewsCollectionRunStatus.SKIPPED;
		}
		if (postProcessing.failureReason() != null && keywordExecutions.stream().noneMatch(it -> it.status().isCompleted())) {
			return NewsCollectionRunStatus.FAILED;
		}
		if (postProcessing.failureReason() != null) {
			return NewsCollectionRunStatus.PARTIAL;
		}
		boolean anyFailed = keywordExecutions.stream().anyMatch(it -> it.status().isFailure());
		return anyFailed ? NewsCollectionRunStatus.PARTIAL : NewsCollectionRunStatus.COMPLETED;
	}

	private NewsCollectionRunCompletion completion(
		long runId,
		NewsCollectionRunStatus status,
		OffsetDateTime finishedAt,
		int keywordCount,
		List<NaverNewsDailyPipelineKeywordExecution> keywordExecutions,
		PostProcessingResult postProcessing,
		NewsCollectionNotificationStatus notificationStatus,
		String notificationFailureReason
	) {
		NewsArticleRelevanceGateResult relevance = postProcessing.relevanceResult();
		NewsSignalFeatureExtractionResult extraction = postProcessing.extractionResult();
		NewsSignalObsidianExportResult export = postProcessing.exportResult();
			return NewsCollectionRunCompletion.from(
				runId,
				status,
				finishedAt,
				new NewsCollectionRunCompletion.CollectionMetrics(
					keywordCount,
					keywordExecutions.stream().mapToLong(it -> it.ingestResult().read()).sum(),
					keywordExecutions.stream().mapToLong(it -> it.ingestResult().observed()).sum(),
					keywordExecutions.stream().mapToLong(it -> it.ingestResult().duplicateSkipped()).sum()
				),
				new NewsCollectionRunCompletion.RelevanceMetrics(
					relevance.evaluated(),
					relevance.kept(),
					relevance.skippedIrrelevant()
				),
				new NewsCollectionRunCompletion.ExtractionMetrics(
					extraction.evaluated(),
					extraction.extracted(),
					extraction.duplicateFeatureSkipped()
				),
				exportMetrics(export),
				new NewsCollectionRunCompletion.NotificationResult(notificationStatus, notificationFailureReason),
				failureReason(keywordExecutions, postProcessing)
			);
		}

	private static NewsCollectionRunCompletion.ExportMetrics exportMetrics(NewsSignalObsidianExportResult export) {
		if (export == null) {
			return NewsCollectionRunCompletion.ExportMetrics.none();
		}
		return new NewsCollectionRunCompletion.ExportMetrics(
			export.featureCount(),
			export.date().toString(),
			export.path() == null ? null : export.path().toString(),
			export.articleCount(),
			export.truncated()
		);
	}

	private static String failureReason(
		List<NaverNewsDailyPipelineKeywordExecution> keywordExecutions,
		PostProcessingResult postProcessing
	) {
		List<String> reasons = new ArrayList<>();
		for (NaverNewsDailyPipelineKeywordExecution execution : keywordExecutions) {
			if (execution.failureReason() != null && !execution.failureReason().isBlank()) {
				reasons.add(execution.keyword().queryText() + "=" + execution.failureReason());
			}
		}
		if (postProcessing.failureReason() != null && !postProcessing.failureReason().isBlank()) {
			reasons.add(postProcessing.failureReason());
		}
		String joined = String.join("; ", reasons);
		return joined.isBlank() ? null : sanitize(joined);
	}

	private static String failureReason(RuntimeException exception) {
		String reason = exception.getClass().getSimpleName();
		if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
			reason = reason + ": " + exception.getMessage();
		}
		return reason;
	}

	private static String sanitize(String value) {
		return NaverNewsDailyPipelineMessageFormatter.sanitizeSensitiveValues(value);
	}

	private record PostProcessingResult(
		NewsArticleRelevanceGateResult relevanceResult,
		NewsSignalFeatureExtractionResult extractionResult,
		NewsSignalObsidianExportResult exportResult,
		String failureReason
	) {

		static PostProcessingResult empty() {
			return new PostProcessingResult(
				NewsArticleRelevanceGateResult.empty(),
				NewsSignalFeatureExtractionResult.empty(),
				null,
				null
			);
		}

		static PostProcessingResult failed(String reason) {
			return failed(
				reason,
				NewsArticleRelevanceGateResult.empty(),
				NewsSignalFeatureExtractionResult.empty(),
				null
			);
		}

		static PostProcessingResult failed(
			String reason,
			NewsArticleRelevanceGateResult relevance,
			NewsSignalFeatureExtractionResult extraction,
			NewsSignalObsidianExportResult export
		) {
			return new PostProcessingResult(relevance, extraction, export, sanitize(reason));
		}
	}

	private record NotificationResult(NewsCollectionNotificationStatus status, String failureReason) {
	}
}
