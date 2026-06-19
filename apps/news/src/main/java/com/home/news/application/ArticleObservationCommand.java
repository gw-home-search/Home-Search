package com.home.news.application;

import java.time.Instant;
import java.time.LocalDate;

import com.home.domain.news.NewsObservationStatus;
import com.home.domain.news.NewsSource;

public record ArticleObservationCommand(
	NewsSource source,
	String sourceKey,
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
