package com.home.application.news;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;

public record NewsArticleObservationCommand(
	String source,
	String sourceKey,
	String publisher,
	String title,
	String url,
	String providerUrl,
	String snippet,
	OffsetDateTime publishedAt,
	OffsetDateTime providerPubAt,
	OffsetDateTime firstSeenAt,
	OffsetDateTime collectedAt,
	OffsetDateTime updatedAt,
	LocalDate newsDateKst,
	String rawProviderPayload,
	String payloadHash,
	NewsArticleObservationStatus ingestStatus,
	String failureReason
) {

	public NewsArticleObservationCommand {
		source = requireText(source, "source");
		sourceKey = requireText(sourceKey, "sourceKey");
		publisher = requireText(publisher, "publisher");
		title = requireText(title, "title");
		url = requireText(url, "url");
		firstSeenAt = Objects.requireNonNull(firstSeenAt, "firstSeenAt must not be null");
		collectedAt = Objects.requireNonNull(collectedAt, "collectedAt must not be null");
		newsDateKst = Objects.requireNonNull(newsDateKst, "newsDateKst must not be null");
		rawProviderPayload = (rawProviderPayload == null || rawProviderPayload.isBlank()) ? "{}" : rawProviderPayload;
		ingestStatus = Objects.requireNonNull(ingestStatus, "ingestStatus must not be null");
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value.trim();
	}
}
