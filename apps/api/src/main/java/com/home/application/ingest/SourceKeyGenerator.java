package com.home.application.ingest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

final class SourceKeyGenerator {

	String generate(String source, OpenApiTradeItem item) {
		String canonicalSource = normalize(source).toUpperCase(Locale.ROOT);
		String material = String.join("|",
			canonicalSource,
			normalize(item.aptSeq()),
			normalize(item.sggCd()),
			normalize(item.umdCd()),
			normalizeInteger(item.dealYear()),
			normalizeInteger(item.dealMonth()),
			normalizeInteger(item.dealDay()),
			normalizeInteger(item.floor()),
			normalizeDecimal(item.exclArea()),
			normalizeAmount(item.dealAmount()),
			normalize(item.aptDong()),
			normalize(item.jibun())
		);
		return canonicalSource + ":" + sha256(material);
	}

	String hashPayload(String payload) {
		return sha256(payload == null ? "" : payload);
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim().replaceAll("\\s+", " ");
	}

	private static String normalizeInteger(Integer value) {
		return value == null ? "" : value.toString();
	}

	private static String normalizeDecimal(Double value) {
		BigDecimal normalized = TradeExclAreaNormalizer.normalize(value);
		return normalized == null ? "" : normalized.toPlainString();
	}

	private static String normalizeAmount(String value) {
		return normalize(value).replace(",", "").replace(" ", "");
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}
}
