package com.home.infrastructure.external.naver;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

import com.home.application.news.NewsArticleObservationIngestResult;
import com.home.application.news.NewsArticleRelevanceGateResult;
import com.home.application.news.NewsArticleRelevanceGateService;
import com.home.application.news.NewsSignalFeatureExtractionResult;
import com.home.application.news.NewsSignalFeatureExtractionService;
import com.home.application.news.NewsSignalObsidianExportCommand;
import com.home.application.news.NewsSignalObsidianExportResult;
import com.home.application.news.NewsSignalObsidianExportService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

class NaverNewsSignalPipelineApplicationRunner implements ApplicationRunner, Ordered {

	private static final Logger log = LoggerFactory.getLogger(NaverNewsSignalPipelineApplicationRunner.class);

	private final NaverNewsOneShotIngestRunner ingestRunner;
	private final NewsArticleRelevanceGateService relevanceGateService;
	private final NewsSignalFeatureExtractionService featureExtractionService;
	private final NewsSignalObsidianExportService obsidianExportService;
	private final NaverNewsSignalPipelineProperties pipelineProperties;
	private final NaverNewsOneShotIngestProperties ingestProperties;
	private final NaverNewsSearchProperties searchProperties;
	private final Clock clock;

	NaverNewsSignalPipelineApplicationRunner(
		NaverNewsOneShotIngestRunner ingestRunner,
		NewsArticleRelevanceGateService relevanceGateService,
		NewsSignalFeatureExtractionService featureExtractionService,
		NewsSignalObsidianExportService obsidianExportService,
		NaverNewsSignalPipelineProperties pipelineProperties,
		NaverNewsOneShotIngestProperties ingestProperties,
		NaverNewsSearchProperties searchProperties,
		Clock clock
	) {
		this.ingestRunner = Objects.requireNonNull(ingestRunner, "ingestRunner must not be null");
		this.relevanceGateService = Objects.requireNonNull(relevanceGateService, "relevanceGateService must not be null");
		this.featureExtractionService = Objects.requireNonNull(
			featureExtractionService,
			"featureExtractionService must not be null"
		);
		this.obsidianExportService = Objects.requireNonNull(obsidianExportService, "obsidianExportService must not be null");
		this.pipelineProperties = Objects.requireNonNull(pipelineProperties, "pipelineProperties must not be null");
		this.ingestProperties = Objects.requireNonNull(ingestProperties, "ingestProperties must not be null");
		this.searchProperties = Objects.requireNonNull(searchProperties, "searchProperties must not be null");
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!pipelineProperties.enabled()) {
			return;
		}
		NaverNewsSearchRequest request = ingestProperties.request();
		searchProperties.requiredClientId();
		searchProperties.requiredClientToken();
		if (ingestProperties.preflightOnly()) {
			log.info(
				"Naver News signal pipeline preflight completed baseUrl={} path={} query={} display={} start={} sort={}",
				searchProperties.baseUrl(),
				searchProperties.path(),
				request.query(),
				request.display(),
				request.start(),
				request.sort()
			);
			return;
		}

		NewsArticleObservationIngestResult ingestResult = ingestRunner.ingest(request);
		NewsArticleRelevanceGateResult relevanceResult =
			relevanceGateService.evaluateObserved(pipelineProperties.relevanceLimit());
		NewsSignalFeatureExtractionResult extractionResult =
			featureExtractionService.extractPending(pipelineProperties.featureExtractionLimit());
		LocalDate exportDate = pipelineProperties.obsidianDate() == null
			? LocalDate.now(clock.withZone(pipelineProperties.obsidianZoneId()))
			: pipelineProperties.obsidianDate();
		NewsSignalObsidianExportResult exportResult = obsidianExportService.exportDaily(
			new NewsSignalObsidianExportCommand(
				pipelineProperties.obsidianOutputRoot(),
				exportDate,
				pipelineProperties.obsidianZoneId(),
				pipelineProperties.obsidianMaxRows()
			)
		);

		log.info(
			"Naver News signal pipeline completed query={} read={} observed={} duplicateSkipped={} relevanceEvaluated={} relevanceKept={} relevanceSkippedIrrelevant={} extractionEvaluated={} extracted={} exportDate={} exportPath={} exportFeatures={} exportArticles={} exportTruncated={}",
			request.query(),
			ingestResult.read(),
			ingestResult.observed(),
			ingestResult.duplicateSkipped(),
			relevanceResult.evaluated(),
			relevanceResult.kept(),
			relevanceResult.skippedIrrelevant(),
			extractionResult.evaluated(),
			extractionResult.extracted(),
			exportResult.date(),
			exportResult.path(),
			exportResult.featureCount(),
			exportResult.articleCount(),
			exportResult.truncated()
		);
	}

	@Override
	public int getOrder() {
		return ApplicationRunnerOrders.NEWS_SIGNAL_PIPELINE;
	}
}
