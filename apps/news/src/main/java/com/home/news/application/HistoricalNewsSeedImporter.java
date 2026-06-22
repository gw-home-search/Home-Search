package com.home.news.application;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.home.domain.news.NewsAvailabilityBasis;
import com.home.domain.news.NewsDiscoveryMethod;
import com.home.domain.news.NewsModelDatasetTier;
import com.home.domain.news.NewsObservationStatus;
import com.home.domain.news.NewsRegionBucket;
import com.home.domain.news.NewsResearchSeedRunStatus;
import com.home.domain.news.NewsSignalTopic;
import com.home.domain.news.NewsSource;
import com.home.domain.news.NewsVerificationStatus;
import com.home.domain.news.SignalEvidenceLevel;
import com.home.domain.news.SignalImpactDirection;
import com.home.domain.news.SignalImpactTarget;
import com.home.domain.news.SignalSentiment;
import com.home.news.NewsRuntimeProperties;
import com.home.news.infrastructure.persistence.JdbcNewsRepository;
import com.home.news.support.JsonStrings;
import com.home.news.support.TextDigests;

public class HistoricalNewsSeedImporter {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final String DATE_PRECISION = "DATE";

	private final JdbcNewsRepository repository;
	private final NewsRuntimeProperties properties;
	private final Clock clock;
	private final ObjectMapper objectMapper;

	public HistoricalNewsSeedImporter(
		JdbcNewsRepository repository,
		NewsRuntimeProperties properties,
		Clock clock,
		ObjectMapper objectMapper
	) {
		this.repository = repository;
		this.properties = properties;
		this.clock = clock;
		this.objectMapper = objectMapper;
	}

	public HistoricalNewsSeedImportResult importApprovedNotes(Path rootDir) {
		List<ReviewedHistoricalNewsNote> notes = readNotes(rootDir);
		int rejectedCount = (int) notes.stream()
			.filter(note -> note.verificationStatus() == NewsVerificationStatus.REJECTED)
			.count();
		List<ReviewedHistoricalNewsNote> approvedNotes = notes.stream()
			.filter(note -> note.verificationStatus().isImportableManualApproval())
			.toList();
		long runId = repository.createAiResearchSeedRun(seedRunCommand(notes, approvedNotes));
		int importedCount = 0;
		int duplicateCount = 0;
		int failedCount = 0;
		String failureReason = null;
		for (ReviewedHistoricalNewsNote note : approvedNotes) {
			try {
				ArticleObservationResult observation = repository.insertObservationIfAbsent(observationCommand(note, runId));
				SignalFeatureResult feature = repository.insertFeatureIfAbsent(featureCommand(observation, note));
				if (observation.created() && feature.created()) {
					importedCount++;
				}
				else {
					duplicateCount++;
				}
			}
			catch (RuntimeException ex) {
				failedCount++;
				failureReason = sanitizedFailureReason(ex);
			}
		}
		NewsResearchSeedRunStatus status = failedCount == 0
			? NewsResearchSeedRunStatus.SUCCEEDED
			: importedCount > 0 ? NewsResearchSeedRunStatus.PARTIAL : NewsResearchSeedRunStatus.FAILED;
		repository.finalizeAiResearchSeedRun(runId, status, notes.size(), approvedNotes.size(), rejectedCount, failureReason);
		return new HistoricalNewsSeedImportResult(
			notes.size(),
			importedCount,
			duplicateCount,
			notes.size() - approvedNotes.size(),
			failedCount
		);
	}

	private List<ReviewedHistoricalNewsNote> readNotes(Path rootDir) {
		if (!Files.exists(rootDir)) {
			throw new NewsCollectionException("research seed note directory does not exist: " + rootDir);
		}
		try (var stream = Files.walk(rootDir)) {
			return stream
				.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(".md"))
				.filter(path -> !isManifestPath(rootDir, path))
				.sorted(Comparator.comparing(Path::toString))
				.map(path -> parseNote(rootDir, path))
				.toList();
		}
		catch (IOException ex) {
			throw new NewsCollectionException("research seed note scan failed", ex);
		}
	}

	private boolean isManifestPath(Path rootDir, Path notePath) {
		String relativePath = relativePath(rootDir, notePath);
		return relativePath.startsWith("_manifest/") || relativePath.contains("/_manifest/");
	}

	private ReviewedHistoricalNewsNote parseNote(Path rootDir, Path notePath) {
		try {
			String content = Files.readString(notePath);
			Map<String, String> frontMatter = frontMatter(content);
			Map<String, String> bodyMetadata = bodyMetadata(content);
			NewsVerificationStatus verificationStatus = enumValue(NewsVerificationStatus.class, required(frontMatter, "verification_status"));
			NewsSource source = enumValue(NewsSource.class, required(frontMatter, "source"));
			NewsDiscoveryMethod discoveryMethod = enumValue(NewsDiscoveryMethod.class, required(frontMatter, "discovery_method"));
			NewsAvailabilityBasis availabilityBasis = enumValue(NewsAvailabilityBasis.class, required(frontMatter, "availability_basis"));
			NewsModelDatasetTier modelDatasetTier = enumValue(NewsModelDatasetTier.class, required(frontMatter, "model_dataset_tier"));
			NewsRegionBucket regionBucket = enumValue(NewsRegionBucket.class, required(frontMatter, "region_bucket"));
			NewsSignalTopic topic = enumValue(NewsSignalTopic.class, required(frontMatter, "topic"));
			SignalImpactTarget impactTarget = enumValue(SignalImpactTarget.class, required(frontMatter, "impact_target"));
			SignalImpactDirection impactDirectionHint = enumValue(SignalImpactDirection.class, required(frontMatter, "impact_direction_hint"));
			LocalDate publishedDate = LocalDate.parse(required(frontMatter, "published_date"));
			validateSourcePolicy(source, discoveryMethod, availabilityBasis, modelDatasetTier, notePath);
			return new ReviewedHistoricalNewsNote(
				relativePath(rootDir, notePath),
				verificationStatus,
				source,
				discoveryMethod,
				availabilityBasis,
				modelDatasetTier,
				required(frontMatter, "title"),
				required(frontMatter, "publisher"),
				publishedDate,
				required(frontMatter, "url"),
				required(frontMatter, "url_citation"),
				regionBucket,
				topic,
				impactTarget,
				impactDirectionHint,
				optional(frontMatter, "signal_month", optional(frontMatter, "query_month", publishedDate.toString().substring(0, 7))),
				optional(frontMatter, "query_bucket", regionBucket.name()),
				optional(frontMatter, "model", requiredModel(properties.getResearchSeed())),
				optional(frontMatter, "prompt_version", properties.getResearchSeed().getPromptVersion()),
				optional(frontMatter, "schema_version", properties.getResearchSeed().getSchemaVersion()),
				optional(frontMatter, "screening_version", properties.getResearchSeed().getScreeningVersion()),
				optional(frontMatter, "score_signal_strength", ""),
				optional(frontMatter, "model_utility", ""),
				required(frontMatter, "confidence"),
				optional(frontMatter, "reason_codes", "[]"),
				optional(frontMatter, "screening_reasons", "[]"),
				optional(frontMatter, "candidate_hash", ""),
				optional(frontMatter, "reviewed_at", ""),
				optional(frontMatter, "review_decision_reason", ""),
				frontMatter.getOrDefault("reviewed_by", ""),
				optional(frontMatter, "source_file", ""),
				optional(frontMatter, "source_row_number", ""),
				optional(frontMatter, "provider_record_id", ""),
				optional(frontMatter, "original_url", ""),
				optional(frontMatter, "keywords", bodyMetadata.getOrDefault("키워드", "")),
				optional(frontMatter, "extracted_terms", bodyMetadata.getOrDefault("특성추출", "")),
				optional(frontMatter, "region_entities", bodyMetadata.getOrDefault("지역 개체", "")),
				optional(frontMatter, "organization_entities", bodyMetadata.getOrDefault("기관 개체", ""))
			);
		}
		catch (NewsCollectionException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new NewsCollectionException("research seed note parsing failed: " + notePath, ex);
		}
	}

	private Map<String, String> frontMatter(String content) {
		if (!content.startsWith("---")) {
			throw new NewsCollectionException("research seed note is missing frontmatter");
		}
		int end = content.indexOf("\n---", 3);
		if (end < 0) {
			throw new NewsCollectionException("research seed note frontmatter is not closed");
		}
		String block = content.substring(3, end);
		Map<String, String> values = new LinkedHashMap<>();
		for (String line : block.split("\\R")) {
			String stripped = line.strip();
			if (stripped.isEmpty() || stripped.startsWith("#")) {
				continue;
			}
			int separator = stripped.indexOf(':');
			if (separator < 1) {
				throw new NewsCollectionException("research seed frontmatter line must be key: value");
			}
			values.put(stripped.substring(0, separator).strip(), unquote(stripped.substring(separator + 1).strip()));
		}
		return values;
	}

	private void validateSourcePolicy(
		NewsSource source,
		NewsDiscoveryMethod discoveryMethod,
		NewsAvailabilityBasis availabilityBasis,
		NewsModelDatasetTier modelDatasetTier,
		Path notePath
	) {
		boolean validAiSeed = source == NewsSource.AI_ASSISTED_WEB_RESEARCH
			&& discoveryMethod == NewsDiscoveryMethod.OPENAI_WEB_SEARCH
			&& availabilityBasis.isAiAssistedSeed()
			&& modelDatasetTier == NewsModelDatasetTier.EXPERIMENTAL_SEED;
		boolean validBigKindsSeed = source == NewsSource.BIGKINDS_CSV
			&& discoveryMethod == NewsDiscoveryMethod.PROVIDER_EXPORT
			&& availabilityBasis == NewsAvailabilityBasis.LICENSED_HISTORICAL_EXPORT
			&& modelDatasetTier == NewsModelDatasetTier.EXPERIMENTAL_SEED;
		if (!validAiSeed && !validBigKindsSeed) {
			throw new NewsCollectionException("research seed note metadata does not match seed policy: " + notePath);
		}
	}

	private Map<String, String> bodyMetadata(String content) {
		int end = content.indexOf("\n---", 3);
		if (end < 0) {
			return Map.of();
		}
		String body = content.substring(end + 4);
		Map<String, String> values = new LinkedHashMap<>();
		for (String line : body.split("\\R")) {
			String stripped = line.strip();
			if (!stripped.startsWith("- ")) {
				continue;
			}
			String item = stripped.substring(2).strip();
			int separator = item.indexOf(':');
			if (separator < 1) {
				continue;
			}
			values.put(item.substring(0, separator).strip(), item.substring(separator + 1).strip());
		}
		return values;
	}

	private ArticleObservationCommand observationCommand(ReviewedHistoricalNewsNote note, long runId) {
		String canonicalUrl = canonicalUrl(note.url());
		String sourceKey = sourceKey(note, canonicalUrl);
		Instant publishedAt = note.publishedDate().atStartOfDay(KST).toInstant();
		Instant now = Instant.now(clock);
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("source", note.source().name());
		payload.put("discovery_method", note.discoveryMethod().name());
		payload.put("availability_basis", note.availabilityBasis().name());
		payload.put("model_dataset_tier", note.modelDatasetTier().name());
		payload.put("published_date_precision", DATE_PRECISION);
		payload.put("url_citation", note.urlCitation());
		payload.put("region_bucket", note.regionBucket().name());
		payload.put("topic", note.topic().name());
		payload.put("impact_target", note.impactTarget().name());
		payload.put("impact_direction_hint", note.impactDirectionHint().name());
		payload.put("signal_month", note.signalMonth());
		payload.put("query_bucket", note.queryBucket());
		if (note.source() == NewsSource.AI_ASSISTED_WEB_RESEARCH) {
			payload.put("model", note.model());
			payload.put("prompt_version", note.promptVersion());
			payload.put("schema_version", note.schemaVersion());
			payload.put("screening_version", note.screeningVersion());
			putIfNotBlank(payload, "score_signal_strength", note.scoreSignalStrength());
			putIfNotBlank(payload, "model_utility", note.modelUtility());
			payload.put("reason_codes", note.reasonCodes());
		}
		payload.put("screening_reasons", note.screeningReasons());
		payload.put("candidate_hash", note.candidateHash());
		payload.put("reviewed_at", note.reviewedAt());
		payload.put("review_decision_reason", note.reviewDecisionReason());
		payload.put("reviewed_by", reviewer(note));
		payload.put("review_note_path", note.reviewNotePath());
		putIfNotBlank(payload, "source_file", note.sourceFile());
		putIfNotBlank(payload, "source_row_number", note.sourceRowNumber());
		putIfNotBlank(payload, "provider_record_id", note.providerRecordId());
		putIfNotBlank(payload, "original_url", note.originalUrl());
		putIfNotBlank(payload, "keywords", note.keywords());
		putIfNotBlank(payload, "extracted_terms", note.extractedTerms());
		putIfNotBlank(payload, "region_entities", note.regionEntities());
		putIfNotBlank(payload, "organization_entities", note.organizationEntities());
		String payloadJson = JsonStrings.compact(objectMapper, payload);
		return new ArticleObservationCommand(
			note.source(),
			sourceKey,
			note.discoveryMethod(),
			note.availabilityBasis(),
			NewsVerificationStatus.MANUAL_APPROVED,
			note.modelDatasetTier(),
			note.reviewNotePath(),
			runId,
			note.publisher(),
			note.title(),
			canonicalUrl,
			note.urlCitation(),
			null,
			publishedAt,
			publishedAt,
			now,
			now,
			note.publishedDate(),
			payloadJson,
			TextDigests.sha256Hex(payloadJson),
			NewsObservationStatus.OBSERVED
		);
	}

	private SignalFeatureCommand featureCommand(ArticleObservationResult observation, ReviewedHistoricalNewsNote note) {
		NewsRuntimeProperties.ResearchSeed seed = properties.getResearchSeed();
		String extractionVersion = seed.getSchemaVersion();
		String model = requiredModel(seed);
		repository.insertSignalProfileIfAbsent(new SignalProfileCommand(
			extractionVersion,
			"OPENAI",
			model,
			seed.getPromptVersion(),
			seed.getSchemaVersion(),
			TextDigests.sha256Hex("prompt:" + seed.getPromptVersion()),
			TextDigests.sha256Hex("schema:" + seed.getSchemaVersion()),
			true
		));
		ArrayNode regionTags = objectMapper.createArrayNode().add(note.regionBucket().name());
		ArrayNode topicTags = objectMapper.createArrayNode().add(note.topic().name());
		ArrayNode complexCandidates = objectMapper.createArrayNode();
		ObjectNode structuredOutput = objectMapper.createObjectNode();
		structuredOutput.set("region_tags", regionTags);
		structuredOutput.set("complex_candidates", complexCandidates);
		structuredOutput.set("topic_tags", topicTags);
		structuredOutput.put("impact_target", note.impactTarget().name());
		structuredOutput.put("impact_direction", note.impactDirectionHint().name());
		structuredOutput.put("sentiment", SignalSentiment.neutral.name());
		BigDecimal confidence = confidence(note.confidence());
		structuredOutput.put("confidence", confidence);
		structuredOutput.put("evidence_level", SignalEvidenceLevel.title.name());
		putIfNotBlank(structuredOutput, "model_utility", note.modelUtility());
		putIfNotBlank(structuredOutput, "score_signal_strength", note.scoreSignalStrength());
		structuredOutput.put("reason_codes", note.reasonCodes());
		structuredOutput.put("screening_version", note.screeningVersion());
		String inputHash = TextDigests.sha256Hex(String.join("|",
			observation.source().name(),
			observation.sourceKey(),
			observation.title(),
			note.reviewNotePath(),
			extractionVersion
		));
		return new SignalFeatureCommand(
			observation.id(),
			observation.source(),
			observation.sourceKey(),
			YearMonth.parse(note.signalMonth()).atDay(1),
			observation.firstSeenAt(),
			JsonStrings.compact(objectMapper, regionTags),
			JsonStrings.compact(objectMapper, complexCandidates),
			JsonStrings.compact(objectMapper, topicTags),
			note.impactTarget().name(),
			note.impactDirectionHint().name(),
			SignalSentiment.neutral.name(),
			confidence.toPlainString(),
			extractionVersion,
			SignalEvidenceLevel.title.name(),
			model,
			seed.getPromptVersion(),
			inputHash,
			JsonStrings.compact(objectMapper, structuredOutput)
		);
	}

	private AiResearchSeedRunCommand seedRunCommand(List<ReviewedHistoricalNewsNote> notes, List<ReviewedHistoricalNewsNote> approvedNotes) {
		List<String> buckets = new ArrayList<>(approvedNotes.stream()
			.map(note -> note.regionBucket().name())
			.distinct()
			.sorted()
			.toList());
		if (buckets.isEmpty()) {
			buckets.addAll(properties.getResearchSeed().getPilotBuckets());
		}
		String bucketListJson = JsonStrings.compact(objectMapper, objectMapper.valueToTree(buckets));
		String manifest = notes.stream()
			.map(note -> note.reviewNotePath() + "|" + note.verificationStatus().name())
			.sorted()
			.reduce("", (left, right) -> left + "\n" + right);
		NewsRuntimeProperties.ResearchSeed seed = properties.getResearchSeed();
		return new AiResearchSeedRunCommand(
			seed.getPeriodStart(),
			seed.getPeriodEnd(),
			bucketListJson,
			seed.getTargetCandidatesPerBucket(),
			requiredModel(seed),
			seed.getPromptVersion(),
			seed.getSchemaVersion(),
			TextDigests.sha256Hex(manifest)
		);
	}

	private String sourceKey(ReviewedHistoricalNewsNote note, String canonicalUrl) {
		if (note.source() == NewsSource.BIGKINDS_CSV) {
			if (!note.providerRecordId().isBlank()) {
				return NewsSource.BIGKINDS_CSV.name() + ":" + note.providerRecordId().strip();
			}
			return NewsSource.BIGKINDS_CSV.name() + ":" + TextDigests.sha256Hex(String.join("|",
				note.sourceFile(),
				note.sourceRowNumber(),
				note.publisher(),
				note.title(),
				note.publishedDate().toString()
			));
		}
		return NewsSource.AI_ASSISTED_WEB_RESEARCH.name() + ":"
			+ TextDigests.sha256Hex(String.join("|", canonicalUrl, note.publishedDate().toString(), note.publisher(), note.title()));
	}

	private String canonicalUrl(String url) {
		try {
			URI uri = URI.create(url);
			return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null).toString();
		}
		catch (Exception ex) {
			return url;
		}
	}

	private String reviewer(ReviewedHistoricalNewsNote note) {
		if (note.reviewedBy() != null && !note.reviewedBy().isBlank()) {
			return note.reviewedBy().strip();
		}
		return properties.getResearchSeed().getDefaultReviewer();
	}

	private BigDecimal confidence(String value) {
		try {
			BigDecimal confidence = new BigDecimal(value);
			if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
				throw new NewsCollectionException("research seed note confidence must be between 0 and 1");
			}
			return confidence;
		}
		catch (NumberFormatException ex) {
			throw new NewsCollectionException("research seed note confidence must be numeric", ex);
		}
	}

	private String relativePath(Path rootDir, Path notePath) {
		return rootDir.toAbsolutePath().normalize()
			.relativize(notePath.toAbsolutePath().normalize())
			.toString()
			.replace('\\', '/');
	}

	private String requiredModel(NewsRuntimeProperties.ResearchSeed seed) {
		if (seed.getModel() == null || seed.getModel().isBlank()) {
			throw new NewsCollectionException("home.news.research-seed.model is required");
		}
		return seed.getModel();
	}

	private String required(Map<String, String> values, String key) {
		String value = values.get(key);
		if (value == null || value.isBlank()) {
			throw new NewsCollectionException("research seed note is missing field " + key);
		}
		return value.strip();
	}

	private String optional(Map<String, String> values, String key, String fallback) {
		String value = values.get(key);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		return value.strip();
	}

	private void putIfNotBlank(ObjectNode payload, String key, String value) {
		if (value != null && !value.isBlank()) {
			payload.put(key, value.strip());
		}
	}

	private String unquote(String value) {
		if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
			return value.substring(1, value.length() - 1);
		}
		return value;
	}

	private <T extends Enum<T>> T enumValue(Class<T> enumType, String value) {
		try {
			return Enum.valueOf(enumType, value);
		}
		catch (IllegalArgumentException ex) {
			throw new NewsCollectionException("research seed note has invalid " + enumType.getSimpleName() + " value " + value, ex);
		}
	}

	private String sanitizedFailureReason(RuntimeException ex) {
		if (ex instanceof NewsCollectionException) {
			return ex.getMessage();
		}
		return "Historical news seed import failed: " + ex.getClass().getSimpleName();
	}

	private record ReviewedHistoricalNewsNote(
		String reviewNotePath,
		NewsVerificationStatus verificationStatus,
		NewsSource source,
		NewsDiscoveryMethod discoveryMethod,
		NewsAvailabilityBasis availabilityBasis,
		NewsModelDatasetTier modelDatasetTier,
		String title,
		String publisher,
		LocalDate publishedDate,
		String url,
		String urlCitation,
		NewsRegionBucket regionBucket,
		NewsSignalTopic topic,
		SignalImpactTarget impactTarget,
		SignalImpactDirection impactDirectionHint,
		String signalMonth,
		String queryBucket,
		String model,
		String promptVersion,
		String schemaVersion,
		String screeningVersion,
		String scoreSignalStrength,
		String modelUtility,
		String confidence,
		String reasonCodes,
		String screeningReasons,
		String candidateHash,
		String reviewedAt,
		String reviewDecisionReason,
		String reviewedBy,
		String sourceFile,
		String sourceRowNumber,
		String providerRecordId,
		String originalUrl,
		String keywords,
		String extractedTerms,
		String regionEntities,
		String organizationEntities
	) {
	}
}
