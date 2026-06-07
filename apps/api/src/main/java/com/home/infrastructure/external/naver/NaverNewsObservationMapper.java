package com.home.infrastructure.external.naver;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.news.NewsArticleObservationCommand;
import com.home.application.news.NewsArticleObservationStatus;

import org.springframework.web.util.HtmlUtils;

class NaverNewsObservationMapper {

	static final String SOURCE = "NAVER_NEWS";

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final Clock clock;
	private final ObjectMapper objectMapper;

	NaverNewsObservationMapper(Clock clock, ObjectMapper objectMapper) {
		this.clock = clock;
		this.objectMapper = objectMapper;
	}

	List<NewsArticleObservationCommand> toObservationCommands(NaverNewsSearchPage page) {
		List<NewsArticleObservationCommand> commands = new ArrayList<>();
		for (NaverNewsSearchItem item : page.items()) {
			commands.add(toObservationCommand(item));
		}
		return commands;
	}

	private NewsArticleObservationCommand toObservationCommand(NaverNewsSearchItem item) {
		String articleUrl = canonicalUrl(item.articleUrl());
		String providerUrl = canonicalUrl(item.link());
		OffsetDateTime firstSeenAt = OffsetDateTime.now(clock);
		OffsetDateTime pubDate = NaverNewsSearchResponseParser.parseDate(item.pubDate());
		String title = cleanText(item.title());
		String snippet = cleanText(item.description());
		String rawProviderPayload = rawProviderPayload(title, articleUrl, providerUrl, snippet, item.pubDate());
		return new NewsArticleObservationCommand(
			SOURCE,
			SOURCE + ":" + sha256(articleUrl),
			publisher(articleUrl),
			title,
			articleUrl,
			providerUrl,
			snippet,
			pubDate,
			pubDate,
			firstSeenAt,
			firstSeenAt,
			null,
			firstSeenAt.atZoneSameInstant(KST).toLocalDate(),
			rawProviderPayload,
			sha256(rawProviderPayload),
			NewsArticleObservationStatus.OBSERVED,
			null
		);
	}

	private String rawProviderPayload(
		String title,
		String articleUrl,
		String providerUrl,
		String snippet,
		String pubDate
	) {
		Map<String, String> payload = new LinkedHashMap<>();
		putIfText(payload, "title", title);
		putIfText(payload, "originallink", articleUrl);
		putIfText(payload, "link", providerUrl);
		putIfText(payload, "description", snippet);
		putIfText(payload, "pubDate", pubDate);
		try {
			return objectMapper.writeValueAsString(payload);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to serialize Naver News provider payload", exception);
		}
	}

	private static void putIfText(Map<String, String> payload, String key, String value) {
		if (value != null && !value.isBlank()) {
			payload.put(key, value);
		}
	}

	private static String cleanText(String value) {
		if (value == null) {
			return "";
		}
		String unescaped = HtmlUtils.htmlUnescape(value);
		return unescaped.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
	}

	private static String canonicalUrl(String value) {
		String trimmed = value == null ? "" : value.trim();
		if (trimmed.isBlank()) {
			throw new IllegalArgumentException("Naver News item URL must not be blank");
		}
		try {
			URI uri = URI.create(trimmed).normalize();
			String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
			String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(java.util.Locale.ROOT);
			URI canonical = new URI(
				scheme,
				uri.getUserInfo(),
				host,
				uri.getPort(),
				uri.getPath(),
				uri.getQuery(),
				null
			);
			return canonical.toString();
		}
		catch (Exception exception) {
			return trimmed.split("#", 2)[0];
		}
	}

	private static String publisher(String articleUrl) {
		try {
			String host = URI.create(articleUrl).getHost();
			if (host == null || host.isBlank()) {
				return "unknown";
			}
			return host.startsWith("www.") ? host.substring(4) : host;
		}
		catch (IllegalArgumentException exception) {
			return "unknown";
		}
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}
}
