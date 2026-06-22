package com.home.news.application;

import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

public final class ForbiddenNewsTextGuard {

	private static final Set<String> FORBIDDEN_KEYS = Set.of(
		"content",
		"body",
		"full_text",
		"html",
		"article_html",
		"summary",
		"article_summary",
		"본문",
		"내용",
		"원문",
		"기사본문"
	);
	private static final String[] FORBIDDEN_TEXT = {
		"본문",
		"내용",
		"원문",
		"기사본문",
		"full_text",
		"article_html",
		"article_summary"
	};

	private ForbiddenNewsTextGuard() {
	}

	public static void rejectForbiddenJsonKeys(JsonNode node) {
		if (node == null || node.isNull()) {
			return;
		}
		if (node.isObject()) {
			node.fields().forEachRemaining(field -> {
				if (FORBIDDEN_KEYS.contains(field.getKey().toLowerCase(Locale.ROOT))) {
					throw new NewsSignalValidationException("forbidden JSON key: " + field.getKey());
				}
				rejectForbiddenJsonKeys(field.getValue());
			});
			return;
		}
		if (node.isArray()) {
			node.forEach(ForbiddenNewsTextGuard::rejectForbiddenJsonKeys);
		}
	}

	public static void rejectForbiddenText(String label, String value) {
		if (value == null || value.isBlank()) {
			return;
		}
		String lower = value.toLowerCase(Locale.ROOT);
		for (String forbidden : FORBIDDEN_TEXT) {
			if (lower.contains(forbidden.toLowerCase(Locale.ROOT))) {
				throw new NewsSignalValidationException(label + " contains forbidden text token: " + forbidden);
			}
		}
	}

	public static boolean hasForbiddenText(String value) {
		try {
			rejectForbiddenText("value", value);
			return false;
		}
		catch (NewsSignalValidationException ex) {
			return true;
		}
	}
}
