package com.home.news.application;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.home.domain.news.NewsAvailabilityBasis;
import com.home.domain.news.NewsDiscoveryMethod;
import com.home.domain.news.NewsModelDatasetTier;
import com.home.domain.news.NewsSource;
import com.home.domain.news.NewsVerificationStatus;
import com.home.news.support.TextDigests;

public class HistoricalNewsResearchNoteGenerator {

	public HistoricalNewsNoteWriteResult writeNotes(Path outputRoot, List<HistoricalNewsCandidate> candidates) {
		int noteCount = 0;
		for (HistoricalNewsCandidate candidate : candidates) {
			if (!candidate.hasCitation()) {
				continue;
			}
			writeNote(outputRoot, candidate);
			noteCount++;
		}
		return new HistoricalNewsNoteWriteResult(candidates.size(), noteCount, outputRoot);
	}

	private void writeNote(Path outputRoot, HistoricalNewsCandidate candidate) {
		Path notePath = outputRoot
			.resolve("news-research-seed")
			.resolve(candidate.regionBucket().name())
			.resolve(String.valueOf(candidate.publishedDate().getYear()))
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
			model_utility: %s
			confidence: %s
			reviewed_by:
			---
			# %s

			- Source link: %s
			- Region bucket: %s
			- Topic: %s
			- Reason codes: %s
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
			frontMatterScalar(candidate.modelUtility()),
			candidate.confidence().toPlainString(),
			markdownText(candidate.title()),
			markdownText(candidate.urlCitation()),
			candidate.regionBucket().name(),
			candidate.topic().name(),
			reasonCodes(candidate)
		);
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

	private String reasonCodes(HistoricalNewsCandidate candidate) {
		return candidate.reasonCodes().stream()
			.map(code -> markdownText(code.toLowerCase(Locale.ROOT)))
			.collect(Collectors.joining(", "));
	}

	private String frontMatterScalar(String value) {
		return markdownText(value).replace("---", "- - -");
	}

	private String markdownText(String value) {
		return value == null ? "" : value.replaceAll("\\R+", " ").replaceAll("\\s+", " ").strip();
	}
}
