package com.home.news.application;

import java.time.Instant;
import java.time.LocalDate;

import com.home.domain.news.NewsAvailabilityBasis;
import com.home.domain.news.NewsDiscoveryMethod;
import com.home.domain.news.NewsModelDatasetTier;
import com.home.domain.news.NewsObservationStatus;
import com.home.domain.news.NewsSource;
import com.home.domain.news.NewsVerificationStatus;

public record ArticleObservationCommand(
	NewsSource source,
	String sourceKey,
	NewsDiscoveryMethod discoveryMethod,
	NewsAvailabilityBasis availabilityBasis,
	NewsVerificationStatus verificationStatus,
	NewsModelDatasetTier modelDatasetTier,
	String reviewNotePath,
	Long aiResearchSeedRunId,
	String publisher,
	String title,
	String url,
	String providerUrl,
	String snippet,
	Instant publishedAt,
	Instant providerPubAt,
	Instant firstSeenAt,
	Instant collectedAt,
	LocalDate newsDateKst,
	String rawProviderPayloadJson,
	String payloadHash,
	NewsObservationStatus ingestStatus
) {
}
