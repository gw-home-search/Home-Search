package com.home.news.infrastructure.external.naver;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.home.domain.news.NewsSource;
import com.home.news.application.NewsArticleMetadata;
import com.home.news.application.NewsCollectionException;
import com.home.news.application.NewsSearchResult;
import com.home.news.support.JsonStrings;
import com.home.news.support.TextDigests;

public class NaverNewsSearchResponseParser {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter NAVER_PUB_DATE_FORMATTER =
		DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

	private final ObjectMapper objectMapper;

	public NaverNewsSearchResponseParser(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public NewsSearchResult parse(String responseBody) {
		try {
			JsonNode root = objectMapper.readTree(responseBody);
			int total = root.path("total").asInt(0);
			int start = root.path("start").asInt(1);
			int display = root.path("display").asInt(0);
			List<NewsArticleMetadata> articles = new ArrayList<>();
			JsonNode items = root.path("items");
			if (items.isArray()) {
				int rank = 1;
				for (JsonNode item : items) {
					articles.add(toArticle(item, total, start, display, rank));
					rank++;
				}
			}
			return new NewsSearchResult(total, start, display, articles);
		}
		catch (Exception ex) {
			throw new NewsCollectionException("Naver News Search response parsing failed", ex);
		}
	}

	private NewsArticleMetadata toArticle(JsonNode item, int total, int start, int display, int rank) {
		String rawTitle = text(item, "title");
		String rawSnippet = text(item, "description");
		String title = cleanHtml(rawTitle);
		String snippet = cleanHtml(rawSnippet);
		String originallink = text(item, "originallink");
		String link = text(item, "link");
		String canonicalUrl = firstNonBlank(originallink, link);
		String providerUrl = requireNonBlank(link, "Naver item link is required");
		Instant providerPubAt = parsePubDate(text(item, "pubDate"));
		String sourceKey = sourceKey(canonicalUrl, providerUrl, providerPubAt, title);
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("title", rawTitle);
		payload.put("originallink", originallink);
		payload.put("link", link);
		payload.put("description", rawSnippet);
		payload.put("pubDate", text(item, "pubDate"));
		payload.put("provider_total", total);
		payload.put("provider_start", start);
		payload.put("provider_display", display);
		payload.put("provider_rank", rank);
		String rawPayload = JsonStrings.compact(objectMapper, payload);
		return new NewsArticleMetadata(
			NewsSource.NAVER_NEWS_SEARCH,
			sourceKey,
			publisher(canonicalUrl),
			requireNonBlank(title, "Naver item title is required"),
			blankToNull(canonicalUrl),
			providerUrl,
			blankToNull(snippet),
			providerPubAt,
			providerPubAt,
			LocalDate.ofInstant(providerPubAt, KST),
			rawPayload,
			TextDigests.sha256Hex(rawPayload)
		);
	}

	private String sourceKey(String canonicalUrl, String providerUrl, Instant providerPubAt, String title) {
		return TextDigests.sha256Hex(String.join("|",
			NewsSource.NAVER_NEWS_SEARCH.name(),
			normalizeIdentityPart(canonicalUrl),
			normalizeIdentityPart(providerUrl),
			providerPubAt.toString(),
			normalizeIdentityPart(title)
		));
	}

	private Instant parsePubDate(String value) {
		String text = requireNonBlank(value, "Naver item pubDate is required");
		return ZonedDateTime.parse(text, NAVER_PUB_DATE_FORMATTER).toInstant();
	}

	private String publisher(String canonicalUrl) {
		if (canonicalUrl == null || canonicalUrl.isBlank()) {
			return "unknown";
		}
		try {
			String host = URI.create(canonicalUrl).getHost();
			if (host == null || host.isBlank()) {
				return "unknown";
			}
			String lower = host.toLowerCase(Locale.ROOT);
			return lower.startsWith("www.") ? lower.substring(4) : lower;
		}
		catch (IllegalArgumentException ex) {
			return "unknown";
		}
	}

	private String cleanHtml(String value) {
		if (value == null) {
			return "";
		}
		String withoutTags = value.replaceAll("(?i)</?b>", "").replaceAll("<[^>]+>", "");
		return htmlUnescape(withoutTags).replaceAll("\\s+", " ").trim();
	}

	private String htmlUnescape(String value) {
		return value
			.replace("&quot;", "\"")
			.replace("&#34;", "\"")
			.replace("&#39;", "'")
			.replace("&apos;", "'")
			.replace("&lt;", "<")
			.replace("&gt;", ">")
			.replace("&amp;", "&");
	}

	private String text(JsonNode item, String fieldName) {
		JsonNode value = item.get(fieldName);
		return value == null || value.isNull() ? "" : value.asText();
	}

	private String firstNonBlank(String first, String second) {
		if (first != null && !first.isBlank()) {
			return first;
		}
		return second == null ? "" : second;
	}

	private String requireNonBlank(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new NewsCollectionException(message);
		}
		return value;
	}

	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private String normalizeIdentityPart(String value) {
		return value == null ? "" : value.replaceAll("\\s+", " ").trim();
	}
}
