package com.home.news.application;

import java.math.BigDecimal;
import java.net.URI;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.home.domain.news.SignalImpactDirection;

class HistoricalNewsCandidateGate {

	private static final BigDecimal MIN_CONFIDENCE = new BigDecimal("0.80");
	private static final Set<String> ALLOWED_REASON_CODES = Set.of(
		"policy",
		"regulation",
		"tax",
		"loan",
		"rate",
		"subscription",
		"supply",
		"reconstruction",
		"redevelopment",
		"transport",
		"infra",
		"school",
		"jeonse",
		"rent",
		"transaction_volume",
		"auction_distress",
		"unsold_inventory",
		"development_project",
		"macro_rate"
	);
	private static final Set<String> BODY_LIKE_KEYS = Set.of(
		"summary",
		"article_summary",
		"content",
		"body",
		"full_text",
		"html",
		"article_html",
		"본문",
		"내용",
		"원문",
		"기사본문"
	);

	List<HistoricalNewsCandidateScreening> screen(List<HistoricalNewsCandidate> candidates) {
		Set<String> acceptedUrls = new HashSet<>();
		List<HistoricalNewsCandidateScreening> results = new ArrayList<>();
		for (HistoricalNewsCandidate candidate : candidates) {
			List<HistoricalNewsCandidateRejectReason> reasons = reasons(candidate, acceptedUrls);
			if (reasons.isEmpty()) {
				acceptedUrls.add(canonicalUrl(candidate.url()));
			}
			results.add(new HistoricalNewsCandidateScreening(candidate, List.copyOf(reasons)));
		}
		return List.copyOf(results);
	}

	private List<HistoricalNewsCandidateRejectReason> reasons(
		HistoricalNewsCandidate candidate,
		Set<String> acceptedUrls
	) {
		List<HistoricalNewsCandidateRejectReason> reasons = new ArrayList<>();
		if (!candidate.hasCitation()) {
			reasons.add(HistoricalNewsCandidateRejectReason.MISSING_CITATION);
		}
		String canonicalUrl = canonicalUrl(candidate.url());
		if (canonicalUrl == null || !isValidHttpUrl(candidate.url()) || !isValidHttpUrl(candidate.urlCitation())) {
			reasons.add(HistoricalNewsCandidateRejectReason.INVALID_URL);
		}
		if (candidate.queryMonth() == null || !YearMonth.from(candidate.publishedDate()).equals(candidate.queryMonth())) {
			reasons.add(HistoricalNewsCandidateRejectReason.OUT_OF_MONTH);
		}
		if (candidate.queryBucket() == null || candidate.regionBucket() != candidate.queryBucket()) {
			reasons.add(HistoricalNewsCandidateRejectReason.BUCKET_MISMATCH);
		}
		if (candidate.modelUtility() == null || !candidate.modelUtility().isHigh()) {
			reasons.add(HistoricalNewsCandidateRejectReason.LOW_UTILITY);
		}
		if (candidate.scoreSignalStrength() == null || !candidate.scoreSignalStrength().isStrong()) {
			reasons.add(HistoricalNewsCandidateRejectReason.WEAK_SIGNAL);
		}
		if (candidate.confidence() == null || candidate.confidence().compareTo(MIN_CONFIDENCE) < 0) {
			reasons.add(HistoricalNewsCandidateRejectReason.LOW_CONFIDENCE);
		}
		if (candidate.impactDirectionHint() == null || candidate.impactDirectionHint() == SignalImpactDirection.unknown) {
			reasons.add(HistoricalNewsCandidateRejectReason.UNKNOWN_DIRECTION);
		}
		if (!validReasonCodes(candidate.reasonCodes())) {
			reasons.add(HistoricalNewsCandidateRejectReason.INVALID_ENUM);
		}
		if (hasBodyLikeReasonCode(candidate.reasonCodes())) {
			reasons.add(HistoricalNewsCandidateRejectReason.BODY_LIKE_FIELD);
		}
		if (reasons.isEmpty() && canonicalUrl != null && acceptedUrls.contains(canonicalUrl)) {
			reasons.add(HistoricalNewsCandidateRejectReason.DUPLICATE_URL);
		}
		return reasons;
	}

	private boolean validReasonCodes(List<String> reasonCodes) {
		if (reasonCodes == null || reasonCodes.isEmpty()) {
			return false;
		}
		return reasonCodes.stream()
			.map(this::normalizedCode)
			.allMatch(ALLOWED_REASON_CODES::contains);
	}

	private boolean hasBodyLikeReasonCode(List<String> reasonCodes) {
		if (reasonCodes == null) {
			return false;
		}
		return reasonCodes.stream()
			.map(this::normalizedCode)
			.anyMatch(BODY_LIKE_KEYS::contains);
	}

	private String normalizedCode(String code) {
		return code == null ? "" : code.replaceAll("\\R+", " ").replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
	}

	private boolean isValidHttpUrl(String url) {
		String canonicalUrl = canonicalUrl(url);
		if (canonicalUrl == null) {
			return false;
		}
		URI uri = URI.create(canonicalUrl);
		return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
			&& uri.getHost() != null
			&& !uri.getHost().isBlank();
	}

	private String canonicalUrl(String url) {
		try {
			URI uri = URI.create(url);
			return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null).toString();
		}
		catch (Exception ex) {
			return null;
		}
	}
}
