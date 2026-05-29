package com.home.infrastructure.external;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.springframework.web.util.UriUtils;

public final class ExternalApiUri {

	private static final Pattern PERCENT_ENCODED_OCTET = Pattern.compile("%[0-9A-Fa-f]{2}");

	private ExternalApiUri() {
	}

	public static URI create(String baseUrl, String path, String query) {
		String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		String normalizedPath = path.startsWith("/") ? path : "/" + path;
		return URI.create(normalizedBaseUrl + normalizedPath + "?" + query);
	}

	public static String queryValue(Object value) {
		return UriUtils.encodeQueryParam(String.valueOf(value), StandardCharsets.UTF_8);
	}

	public static String serviceKeyQueryValue(String configuredKey) {
		String trimmed = configuredKey.trim();
		String decoded = PERCENT_ENCODED_OCTET.matcher(trimmed).find()
			? decodePercentEncoded(trimmed)
			: trimmed;
		return URLEncoder.encode(decoded, StandardCharsets.UTF_8);
	}

	private static String decodePercentEncoded(String value) {
		try {
			return UriUtils.decode(value, StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException exception) {
			return value;
		}
	}
}
