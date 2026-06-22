package com.home.news.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.home.domain.news.RegionMonthSignalEvidenceScope;
import com.home.domain.news.RegionMonthSignalSourceKind;

public class RegionMonthSignalValidator {

	private static final BigDecimal NO_EVIDENCE_CONFIDENCE_MAX = new BigDecimal("0.35");
	private static final BigDecimal WEAKLY_MATCHED_EVIDENCE_CONFIDENCE_MAX = new BigDecimal("0.55");
	private final RegionAliasMatcher regionAliasMatcher = new RegionAliasMatcher();

	public void validate(RegionMonthSignalSnapshot snapshot) {
		if (snapshot.regionBucket() == null) {
			throw new NewsSignalValidationException("region_bucket is required");
		}
		if (snapshot.sourceKind() == null) {
			throw new NewsSignalValidationException("source_kind is required");
		}
		if (snapshot.datasetTier() == null) {
			throw new NewsSignalValidationException("dataset_tier is required");
		}
		if (snapshot.signalMonth() == null || snapshot.signalMonth().getDayOfMonth() != 1) {
			throw new NewsSignalValidationException("signal_month must be first day of month");
		}
		if (snapshot.methodVersion() == null || snapshot.methodVersion().isBlank()) {
			throw new NewsSignalValidationException("method_version is required");
		}
		validateNonNegative("news_count", snapshot.newsCount());
		validateNonNegative("matched_news_count", snapshot.matchedNewsCount());
		validateNonNegative("direct_evidence_count", snapshot.directEvidenceCount());
		validateNonNegative("inherited_evidence_count", snapshot.inheritedEvidenceCount());
		validateScore("policy_positive_score", snapshot.policyPositiveScore());
		validateScore("policy_negative_score", snapshot.policyNegativeScore());
		validateScore("redevelopment_score", snapshot.redevelopmentScore());
		validateScore("transport_score", snapshot.transportScore());
		validateScore("supply_risk_score", snapshot.supplyRiskScore());
		validateScore("sale_market_score", snapshot.saleMarketScore());
		validateScore("rental_market_score", snapshot.rentalMarketScore());
		validateScore("price_up_signal", snapshot.priceUpSignal());
		validateScore("price_down_signal", snapshot.priceDownSignal());
		validateConfidence(snapshot.confidence());
		ForbiddenNewsTextGuard.rejectForbiddenText("aggregate_note", snapshot.aggregateNote());
		List<RegionMonthSignalEvidence> evidence = snapshot.evidence() == null ? List.of() : snapshot.evidence();
		long directCount = evidence.stream().filter(item -> item.evidenceScope() != null && item.evidenceScope().name().equals("DIRECT")).count();
		long inheritedCount = evidence.stream().filter(item -> item.evidenceScope() != null && item.evidenceScope().name().equals("INHERITED")).count();
		if (snapshot.directEvidenceCount() != directCount || snapshot.inheritedEvidenceCount() != inheritedCount) {
			throw new NewsSignalValidationException("evidence counts do not match evidence rows");
		}
		if (directCount == 0 && inheritedCount == 0 && snapshot.confidence().compareTo(NO_EVIDENCE_CONFIDENCE_MAX) > 0) {
			throw new NewsSignalValidationException("rows without evidence must use confidence <= 0.35");
		}
		if (snapshot.sourceKind() == RegionMonthSignalSourceKind.AGENT_WEB_RESEARCH) {
			validateWebResearchSignalQuality(snapshot, evidence, directCount, inheritedCount);
		}
		for (RegionMonthSignalEvidence item : evidence) {
			validateEvidence(snapshot, item);
		}
	}

	private void validateEvidence(RegionMonthSignalSnapshot snapshot, RegionMonthSignalEvidence item) {
		if (item.sourceKey() == null || item.sourceKey().isBlank()) {
			throw new NewsSignalValidationException("evidence source_key is required");
		}
		if (item.title() == null || item.title().isBlank()) {
			throw new NewsSignalValidationException("evidence title is required");
		}
		if (item.publisher() == null || item.publisher().isBlank()) {
			throw new NewsSignalValidationException("evidence publisher is required");
		}
		if (item.evidenceScope() == null) {
			throw new NewsSignalValidationException("evidence_scope is required");
		}
		if (item.evidenceScope() != RegionMonthSignalEvidenceScope.DIRECT && item.evidenceScope() != RegionMonthSignalEvidenceScope.INHERITED) {
			throw new NewsSignalValidationException("evidence_scope is invalid");
		}
		if (item.publishedDate() != null && item.publishedDate().isAfter(LocalDate.now().plusDays(1))) {
			throw new NewsSignalValidationException("published_date is in the future");
		}
		ForbiddenNewsTextGuard.rejectForbiddenText("evidence.title", item.title());
		ForbiddenNewsTextGuard.rejectForbiddenText("evidence.publisher", item.publisher());
	}

	private void validateWebResearchSignalQuality(
		RegionMonthSignalSnapshot snapshot,
		List<RegionMonthSignalEvidence> evidence,
		long directCount,
		long inheritedCount
	) {
		if (directCount == 0 && inheritedCount == 0) {
			throw new NewsSignalValidationException("web research rows require evidence");
		}
		if (!hasAnySignalScore(snapshot)) {
			throw new NewsSignalValidationException("web research rows require at least one signal score");
		}
		if (evidence.stream().noneMatch(item -> isStrongBucketEvidence(snapshot, item))
			&& snapshot.confidence().compareTo(WEAKLY_MATCHED_EVIDENCE_CONFIDENCE_MAX) > 0) {
			throw new NewsSignalValidationException("weakly matched evidence requires confidence <= 0.55");
		}
	}

	private static boolean hasAnySignalScore(RegionMonthSignalSnapshot snapshot) {
		return snapshot.policyPositiveScore() > 0
			|| snapshot.policyNegativeScore() > 0
			|| snapshot.redevelopmentScore() > 0
			|| snapshot.transportScore() > 0
			|| snapshot.supplyRiskScore() > 0
			|| snapshot.saleMarketScore() > 0
			|| snapshot.rentalMarketScore() > 0
			|| snapshot.priceUpSignal() > 0
			|| snapshot.priceDownSignal() > 0;
	}

	private boolean isStrongBucketEvidence(RegionMonthSignalSnapshot snapshot, RegionMonthSignalEvidence item) {
		return item.evidenceScope() == RegionMonthSignalEvidenceScope.DIRECT && matchesBucketTitle(snapshot, item);
	}

	private boolean matchesBucketTitle(RegionMonthSignalSnapshot snapshot, RegionMonthSignalEvidence item) {
		if (!snapshot.regionBucket().isDetailBucket() && snapshot.regionBucket().name().equals("NATIONAL")) {
			return true;
		}
		if (snapshot.regionBucket().name().equals("OTHER")) {
			return matchesOtherTitle(item.title());
		}
		return regionAliasMatcher.match(item.title()).contains(snapshot.regionBucket());
	}

	private static boolean matchesOtherTitle(String title) {
		String text = title == null ? "" : title;
		return text.contains("지방")
			|| text.contains("비수도권")
			|| text.contains("5대 광역시")
			|| text.contains("대구")
			|| text.contains("부산")
			|| text.contains("인천")
			|| text.contains("대전")
			|| text.contains("광주")
			|| text.contains("울산")
			|| text.contains("세종");
	}

	private static void validateNonNegative(String field, int value) {
		if (value < 0) {
			throw new NewsSignalValidationException(field + " must be non-negative");
		}
	}

	private static void validateScore(String field, int value) {
		if (value < 0 || value > 100) {
			throw new NewsSignalValidationException(field + " must be between 0 and 100");
		}
	}

	private static void validateConfidence(BigDecimal value) {
		if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
			throw new NewsSignalValidationException("confidence must be between 0 and 1");
		}
	}
}
