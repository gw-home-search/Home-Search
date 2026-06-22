package com.home.news.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.home.domain.news.NewsModelDatasetTier;
import com.home.domain.news.NewsRegionBucket;
import com.home.domain.news.RegionMonthSignalSourceKind;

public record RegionMonthSignalSnapshot(
	NewsRegionBucket regionBucket,
	LocalDate signalMonth,
	RegionMonthSignalSourceKind sourceKind,
	String methodVersion,
	NewsModelDatasetTier datasetTier,
	int newsCount,
	int matchedNewsCount,
	int directEvidenceCount,
	int inheritedEvidenceCount,
	int policyPositiveScore,
	int policyNegativeScore,
	int redevelopmentScore,
	int transportScore,
	int supplyRiskScore,
	int saleMarketScore,
	int rentalMarketScore,
	int priceUpSignal,
	int priceDownSignal,
	BigDecimal confidence,
	String aggregateNote,
	List<RegionMonthSignalEvidence> evidence
) {
}
