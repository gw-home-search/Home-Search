package com.home.news.application;

import java.time.Clock;
import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.domain.news.ArticleDiscoveryStatus;
import com.home.domain.news.CollectionRunMode;
import com.home.domain.news.CollectionRunStatus;
import com.home.domain.news.NewsAvailabilityBasis;
import com.home.domain.news.NewsDiscoveryMethod;
import com.home.domain.news.NewsKeywordType;
import com.home.domain.news.NewsModelDatasetTier;
import com.home.domain.news.NewsObservationStatus;
import com.home.domain.news.NewsVerificationStatus;
import com.home.news.NewsRuntimeProperties;
import com.home.news.infrastructure.persistence.JdbcNewsRepository;
import com.home.news.support.JsonStrings;
import com.home.news.support.TextDigests;

public class OneKeywordNewsCollectionService {

	private static final int SLICE_MAX_KEYWORDS = 1;
	private static final int SLICE_MAX_DISPLAY = 10;
	private static final int SLICE_MAX_ARTICLES = 10;
	private static final String SCORING_DISABLED_REASON = "SCORING_DISABLED";

	private final JdbcNewsRepository repository;
	private final NewsMetadataClient metadataClient;
	private final NewsSignalScorer signalScorer;
	private final NewsRuntimeProperties properties;
	private final Clock clock;
	private final ObjectMapper objectMapper;

	public OneKeywordNewsCollectionService(
		JdbcNewsRepository repository,
		NewsMetadataClient metadataClient,
		NewsSignalScorer signalScorer,
		NewsRuntimeProperties properties,
		Clock clock,
		ObjectMapper objectMapper
	) {
		this.repository = repository;
		this.metadataClient = metadataClient;
		this.signalScorer = signalScorer;
		this.properties = properties;
		this.clock = clock;
		this.objectMapper = objectMapper;
	}

	public CollectionRunCounts collect(String queryText) {
		validateQuery(queryText);
		int maxKeywords = Math.min(properties.getRunOnce().getMaxKeywords(), SLICE_MAX_KEYWORDS);
		int maxArticles = Math.min(properties.getRunOnce().getMaxArticles(), SLICE_MAX_ARTICLES);
		int displayLimit = Math.min(Math.min(properties.getNaver().getDisplay(), SLICE_MAX_DISPLAY), maxArticles);
		if (maxKeywords < 1 || maxArticles < 1 || displayLimit < 1) {
			throw new NewsCollectionException("home.news run-once limits must be positive");
		}
		long keywordId = repository.upsertManualKeyword(queryText, NewsKeywordType.TOPIC);
		long runId = repository.createRun(CollectionRunMode.RUN_ONCE, queryText.strip(), maxKeywords, displayLimit, maxArticles);
		long runKeywordId = repository.createRunKeyword(
			runId,
			keywordId,
			queryText,
			NewsKeywordType.TOPIC,
			displayLimit,
			properties.getNaver().getSort()
		);

		try {
			return collectRunKeyword(runId, runKeywordId, queryText, displayLimit, maxArticles);
		}
		catch (NewsCollectionException ex) {
			CollectionRunCounts failed = new CollectionRunCounts(
				CollectionRunStatus.FAILED,
				1,
				0,
				0,
				0,
				0,
				0,
				1,
				ex.getMessage()
			);
			repository.updateRunKeywordCounts(runKeywordId, CollectionRunStatus.FAILED, 0, 1, 0, 0, 0, 0, 0, 1, ex.getMessage());
			repository.finalizeRun(runId, failed);
			return failed;
		}
	}

	private CollectionRunCounts collectRunKeyword(long runId, long runKeywordId, String queryText, int displayLimit, int maxArticles) {
		NewsSearchResult searchResult = metadataClient.search(queryText.strip(), displayLimit, properties.getNaver().getSort());
		int fetchedCount = Math.min(searchResult.articles().size(), maxArticles);
		int observedNewCount = 0;
		int observedDuplicateCount = 0;
		int featureCreatedCount = 0;
		int featureSkippedCount = 0;
		int failedCount = 0;
		String failureReason = null;
		for (int index = 0; index < fetchedCount; index++) {
			NewsArticleMetadata article = searchResult.articles().get(index);
			Long articleObservationId = null;
			try {
				ArticleObservationResult observation = repository.insertObservationIfAbsent(toObservationCommand(article));
				articleObservationId = observation.id();
				if (observation.created()) {
					observedNewCount++;
					repository.recordRunArticle(
						runKeywordId,
						observation.id(),
						article.source(),
						article.sourceKey(),
						index + 1,
						article.title(),
						article.providerUrl(),
						ArticleDiscoveryStatus.NEW_OBSERVATION,
						null
					);
				}
				else {
					observedDuplicateCount++;
					repository.recordRunArticle(
						runKeywordId,
						observation.id(),
						article.source(),
						article.sourceKey(),
						index + 1,
						article.title(),
						article.providerUrl(),
						ArticleDiscoveryStatus.DUPLICATE_OBSERVATION,
						null
					);
				}
				if (!properties.getOpenai().isEnabled()) {
					featureSkippedCount++;
					repository.recordRunArticle(
						runKeywordId,
						observation.id(),
						article.source(),
						article.sourceKey(),
						index + 1,
						article.title(),
						article.providerUrl(),
						ArticleDiscoveryStatus.FEATURE_SKIPPED,
						SCORING_DISABLED_REASON
					);
					continue;
				}
				if (!observation.created() && repository.hasSignalFeature(
					observation.source(),
					observation.sourceKey(),
					properties.getOpenai().getExtractionVersion()
				)) {
					featureSkippedCount++;
					continue;
				}
				SignalFeatureResult feature = repository.insertFeatureIfAbsent(toFeatureCommand(observation, signalScorer.score(observation)));
				if (feature.created()) {
					featureCreatedCount++;
					repository.recordRunArticle(
						runKeywordId,
						observation.id(),
						article.source(),
						article.sourceKey(),
						index + 1,
						article.title(),
						article.providerUrl(),
						ArticleDiscoveryStatus.FEATURE_CREATED,
						null
					);
				}
				else {
					featureSkippedCount++;
					repository.recordRunArticle(
						runKeywordId,
						observation.id(),
						article.source(),
						article.sourceKey(),
						index + 1,
						article.title(),
						article.providerUrl(),
						ArticleDiscoveryStatus.FEATURE_SKIPPED,
						null
					);
				}
			}
			catch (RuntimeException ex) {
				failedCount++;
				failureReason = sanitizedFailureReason(ex);
				repository.recordRunArticle(
					runKeywordId,
					articleObservationId,
					article.source(),
					article.sourceKey(),
					index + 1,
					article.title(),
					article.providerUrl(),
					ArticleDiscoveryStatus.FAILED,
					failureReason
				);
			}
		}
		CollectionRunStatus status = failedCount == 0 ? CollectionRunStatus.SUCCEEDED : CollectionRunStatus.PARTIAL;
		repository.updateRunKeywordCounts(
			runKeywordId,
			status,
			searchResult.providerTotal(),
			searchResult.providerStart(),
			searchResult.providerDisplay(),
			fetchedCount,
			observedNewCount,
			observedDuplicateCount,
			featureCreatedCount,
			failedCount,
			failureReason
		);
		CollectionRunCounts counts = new CollectionRunCounts(
			status,
			1,
			fetchedCount,
			observedNewCount,
			observedDuplicateCount,
			featureCreatedCount,
			featureSkippedCount,
			failedCount,
			failureReason
		);
		repository.finalizeRun(runId, counts);
		return counts;
	}

	private ArticleObservationCommand toObservationCommand(NewsArticleMetadata article) {
		Instant now = Instant.now(clock);
		return new ArticleObservationCommand(
			article.source(),
			article.sourceKey(),
			NewsDiscoveryMethod.PROVIDER_API,
			NewsAvailabilityBasis.REALTIME_OBSERVED,
			NewsVerificationStatus.SYSTEM_ACCEPTED,
			NewsModelDatasetTier.OBSERVED_SIGNAL,
			null,
			null,
			article.publisher(),
			article.title(),
			article.url(),
			article.providerUrl(),
			article.snippet(),
			article.publishedAt(),
			article.providerPubAt(),
			now,
			now,
			article.newsDateKst(),
			article.rawProviderPayloadJson(),
			article.payloadHash(),
			NewsObservationStatus.OBSERVED
		);
	}

	private SignalFeatureCommand toFeatureCommand(ArticleObservationResult observation, NewsSignalExtraction extraction) {
		NewsRuntimeProperties.OpenAi openai = properties.getOpenai();
		String extractionVersion = openai.getExtractionVersion();
		String model = requiredModel(openai);
		repository.insertSignalProfileIfAbsent(new SignalProfileCommand(
			extractionVersion,
			"OPENAI",
			model,
			openai.getPromptVersion(),
			openai.getSchemaVersion(),
			TextDigests.sha256Hex("prompt:" + openai.getPromptVersion()),
			TextDigests.sha256Hex("schema:" + openai.getSchemaVersion()),
			true
		));
		String inputHash = TextDigests.sha256Hex(String.join("|",
			observation.source().name(),
			observation.sourceKey(),
			observation.title(),
			observation.snippet() == null ? "" : observation.snippet(),
			extractionVersion
		));
		return new SignalFeatureCommand(
			observation.id(),
			observation.source(),
			observation.sourceKey(),
			observation.newsDateKst(),
			observation.firstSeenAt(),
			JsonStrings.compact(objectMapper, extraction.regionTags()),
			JsonStrings.compact(objectMapper, extraction.complexCandidates()),
			JsonStrings.compact(objectMapper, extraction.topicTags()),
			extraction.impactTarget().name(),
			extraction.impactDirection().name(),
			extraction.sentiment().name(),
			extraction.confidence().toPlainString(),
			extractionVersion,
			extraction.evidenceLevel().name(),
			model,
			openai.getPromptVersion(),
			inputHash,
			JsonStrings.compact(objectMapper, extraction.structuredOutput())
		);
	}

	private void validateQuery(String queryText) {
		if (queryText == null || queryText.isBlank()) {
			throw new NewsCollectionException("home.news.run-once.query-text is required");
		}
	}

	private String requiredModel(NewsRuntimeProperties.OpenAi openai) {
		if (openai.getModel() == null || openai.getModel().isBlank()) {
			throw new NewsCollectionException("home.news.openai.model is required when news scoring is enabled");
		}
		return openai.getModel();
	}

	private String sanitizedFailureReason(RuntimeException ex) {
		if (ex instanceof NewsCollectionException) {
			return ex.getMessage();
		}
		return "News article processing failed: " + ex.getClass().getSimpleName();
	}
}
