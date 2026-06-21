package com.home.news.application;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.home.domain.news.NewsAvailabilityBasis;
import com.home.domain.news.NewsDiscoveryMethod;
import com.home.domain.news.NewsModelDatasetTier;
import com.home.domain.news.NewsSource;
import com.home.domain.news.NewsVerificationStatus;
import com.home.news.NewsRuntimeProperties;
import com.home.news.support.TextDigests;

public class HistoricalNewsResearchNoteGenerator {

	private static final DateTimeFormatter RUN_ID_TIME_FORMAT = DateTimeFormatter
		.ofPattern("yyyyMMddHHmmss")
		.withZone(ZoneOffset.UTC);
	private final NewsRuntimeProperties properties;
	private final Clock clock;
	private final HistoricalNewsCandidateGate gate = new HistoricalNewsCandidateGate();

	public HistoricalNewsResearchNoteGenerator() {
		this(new NewsRuntimeProperties(), Clock.systemDefaultZone());
	}

	public HistoricalNewsResearchNoteGenerator(NewsRuntimeProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	public HistoricalNewsNoteWriteResult writeNotes(Path outputRoot, List<HistoricalNewsCandidate> candidates) {
		List<HistoricalNewsCandidateScreening> screenings = gate.screen(candidates);
		int noteCount = 0;
		for (HistoricalNewsCandidateScreening screening : screenings) {
			if (!screening.accepted()) {
				continue;
			}
			writeNote(outputRoot, screening.candidate());
			noteCount++;
		}
		Map<HistoricalNewsCandidateRejectReason, Integer> rejectedByReason = rejectedByReason(screenings);
		int rejectedCount = (int) screenings.stream().filter(screening -> !screening.accepted()).count();
		writeManifest(outputRoot, runId(candidates), candidates.size(), noteCount, rejectedCount, rejectedByReason);
		return new HistoricalNewsNoteWriteResult(candidates.size(), noteCount, rejectedCount, rejectedByReason, outputRoot);
	}

	private void writeNote(Path outputRoot, HistoricalNewsCandidate candidate) {
		Path notePath = outputRoot
			.resolve("news-research-seed")
			.resolve(candidate.regionBucket().name())
			.resolve(String.valueOf(candidate.publishedDate().getYear()))
			.resolve(candidate.queryMonth().toString())
			.resolve(candidate.publishedDate() + "-" + candidateHash(candidate) + ".md");
		try {
			Files.createDirectories(notePath.getParent());
			Files.writeString(notePath, note(candidate));
		}
		catch (IOException ex) {
			throw new NewsCollectionException("research seed note write failed: " + notePath, ex);
		}
	}

	private String note(HistoricalNewsCandidate candidate) {
		String hash = candidateHash(candidate);
		return """
			---
			verification_status: %s
			source: %s
			discovery_method: %s
			availability_basis: %s
			model_dataset_tier: %s
			title: %s
			publisher: %s
			published_date: %s
			url: %s
			url_citation: %s
			region_bucket: %s
			topic: %s
			impact_target: %s
			impact_direction_hint: %s
			query_month: %s
			query_bucket: %s
			model: %s
			prompt_version: %s
			schema_version: %s
			screening_version: %s
			score_signal_strength: %s
			model_utility: %s
			confidence: %s
			reason_codes: %s
			screening_reasons: []
			candidate_hash: %s
			reviewed_at:
			review_decision_reason:
			reviewed_by:
			---
			- [ ] URL 접속 가능
			- [ ] 기사 날짜가 query_month 내부
			- [ ] 언론사/제목 일치
			- [ ] 지역 영향 직접적
			- [ ] topic/impact target 수정 필요 없음
			- [ ] 가격/전세/거래량/공급/risk 방향성이 설명 가능
			""".formatted(
			NewsVerificationStatus.NEEDS_REVIEW.name(),
			NewsSource.AI_ASSISTED_WEB_RESEARCH.name(),
			NewsDiscoveryMethod.OPENAI_WEB_SEARCH.name(),
			NewsAvailabilityBasis.AI_ASSISTED_RESEARCH_SEED.name(),
			NewsModelDatasetTier.EXPERIMENTAL_SEED.name(),
			frontMatterScalar(candidate.title()),
			frontMatterScalar(candidate.publisher()),
			candidate.publishedDate(),
			frontMatterScalar(candidate.url()),
			frontMatterScalar(candidate.urlCitation()),
			candidate.regionBucket().name(),
			candidate.topic().name(),
			candidate.impactTarget().name(),
			candidate.impactDirectionHint().name(),
			candidate.queryMonth(),
			candidate.queryBucket().name(),
			frontMatterScalar(researchModel()),
			frontMatterScalar(properties.getResearchSeed().getPromptVersion()),
			frontMatterScalar(properties.getResearchSeed().getSchemaVersion()),
			frontMatterScalar(properties.getResearchSeed().getScreeningVersion()),
			candidate.scoreSignalStrength().name(),
			candidate.modelUtility().name(),
			candidate.confidence().toPlainString(),
			array(candidate.reasonCodes()),
			hash
		);
	}

	private void writeManifest(
		Path outputRoot,
		String runId,
		int planned,
		int accepted,
		int rejected,
		Map<HistoricalNewsCandidateRejectReason, Integer> rejectedByReason
	) {
		Path manifestRoot = outputRoot.resolve("news-research-seed").resolve("_manifest");
		try {
			Files.createDirectories(manifestRoot);
			Files.writeString(manifestRoot.resolve(runId + ".json"), manifestJson(runId, planned, accepted, rejected, rejectedByReason));
			Files.writeString(manifestRoot.resolve(runId + ".md"), manifestMarkdown(runId, planned, accepted, rejectedByReason));
		}
		catch (IOException ex) {
			throw new NewsCollectionException("research seed manifest write failed: " + manifestRoot, ex);
		}
	}

	private String manifestJson(
		String runId,
		int planned,
		int accepted,
		int rejected,
		Map<HistoricalNewsCandidateRejectReason, Integer> rejectedByReason
	) {
		int duplicates = rejectedByReason.getOrDefault(HistoricalNewsCandidateRejectReason.DUPLICATE_URL, 0);
		return """
			{
			  "run_id": "%s",
			  "planned": %d,
			  "executed": %d,
			  "accepted": %d,
			  "rejected": %d,
			  "rejected_by_reason": %s,
			  "duplicates": %d,
			  "estimated_cost": null,
			  "model": "%s",
			  "prompt_hash": "%s",
			  "schema_hash": "%s"
			}
			""".formatted(
			runId,
			planned,
			planned,
			accepted,
			rejected,
			rejectedReasonJson(rejectedByReason),
			duplicates,
			jsonText(researchModel()),
			TextDigests.sha256Hex("prompt:" + properties.getResearchSeed().getPromptVersion()),
			TextDigests.sha256Hex("schema:" + properties.getResearchSeed().getSchemaVersion())
		);
	}

	private String manifestMarkdown(
		String runId,
		int planned,
		int accepted,
		Map<HistoricalNewsCandidateRejectReason, Integer> rejectedByReason
	) {
		return """
			---
			run_id: %s
			model: %s
			prompt_version: %s
			schema_version: %s
			screening_version: %s
			---
			planned: %d
			executed: %d
			accepted: %d
			rejected_by_reason: %s
			duplicates: %d
			estimated_cost:
			""".formatted(
			runId,
			frontMatterScalar(researchModel()),
			frontMatterScalar(properties.getResearchSeed().getPromptVersion()),
			frontMatterScalar(properties.getResearchSeed().getSchemaVersion()),
			frontMatterScalar(properties.getResearchSeed().getScreeningVersion()),
			planned,
			planned,
			accepted,
			rejectedReasonText(rejectedByReason),
			rejectedByReason.getOrDefault(HistoricalNewsCandidateRejectReason.DUPLICATE_URL, 0)
		);
	}

	private Map<HistoricalNewsCandidateRejectReason, Integer> rejectedByReason(
		List<HistoricalNewsCandidateScreening> screenings
	) {
		Map<HistoricalNewsCandidateRejectReason, Integer> counts = new EnumMap<>(HistoricalNewsCandidateRejectReason.class);
		for (HistoricalNewsCandidateScreening screening : screenings) {
			for (HistoricalNewsCandidateRejectReason reason : screening.reasons()) {
				counts.merge(reason, 1, Integer::sum);
			}
		}
		return Map.copyOf(counts);
	}

	private String rejectedReasonJson(Map<HistoricalNewsCandidateRejectReason, Integer> rejectedByReason) {
		return rejectedByReason.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.map(entry -> "\"%s\":%d".formatted(entry.getKey().name(), entry.getValue()))
			.collect(Collectors.joining(",", "{", "}"));
	}

	private String rejectedReasonText(Map<HistoricalNewsCandidateRejectReason, Integer> rejectedByReason) {
		return rejectedByReason.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.map(entry -> entry.getKey().name() + "=" + entry.getValue())
			.collect(Collectors.joining(", ", "[", "]"));
	}

	private String candidateHash(HistoricalNewsCandidate candidate) {
		return TextDigests.sha256Hex(String.join("|",
			canonicalUrl(candidate.url()),
			candidate.publishedDate().toString(),
			candidate.publisher(),
			candidate.title()
		)).substring(0, 16);
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

	private String array(List<String> values) {
		return values.stream()
			.map(value -> markdownText(value.toLowerCase(Locale.ROOT)))
			.collect(Collectors.joining(", ", "[", "]"));
	}

	private String runId(List<HistoricalNewsCandidate> candidates) {
		String seed = candidates.stream()
			.map(candidate -> candidate.publishedDate() + "|" + canonicalUrl(candidate.url()) + "|" + candidate.title())
			.sorted()
			.collect(Collectors.joining("\n"));
		return "research-seed-" + RUN_ID_TIME_FORMAT.format(Instant.now(clock)) + "-" + TextDigests.sha256Hex(seed).substring(0, 8);
	}

	private String researchModel() {
		String model = properties.getResearchSeed().getModel();
		if (model != null && !model.isBlank()) {
			return model.strip();
		}
		model = properties.getOpenai().getModel();
		return model == null ? "" : model.strip();
	}

	private String frontMatterScalar(String value) {
		return markdownText(value).replace("---", "- - -");
	}

	private String jsonText(String value) {
		return markdownText(value).replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private String markdownText(String value) {
		return value == null ? "" : value.replaceAll("\\R+", " ").replaceAll("\\s+", " ").strip();
	}
}
