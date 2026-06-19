package com.home.news.application;

import java.time.Instant;
import java.time.LocalDate;

import com.home.domain.news.NewsSource;

public record NewsArticleMetadata(
	NewsSource source,
	String sourceKey,
	String publisher,
	String title,
	String url,
	String providerUrl,
	String snippet,
	Instant publishedAt,
	Instant providerPubAt,
	LocalDate newsDateKst,
	String rawProviderPayloadJson,
	String payloadHash
) {
}
