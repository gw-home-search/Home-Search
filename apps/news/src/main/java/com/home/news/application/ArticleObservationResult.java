package com.home.news.application;

import java.time.Instant;
import java.time.LocalDate;

import com.home.domain.news.NewsSource;

public record ArticleObservationResult(
	long id,
	boolean created,
	NewsSource source,
	String sourceKey,
	String publisher,
	String title,
	String url,
	String providerUrl,
	String snippet,
	Instant providerPubAt,
	Instant firstSeenAt,
	LocalDate newsDateKst
) {
}
