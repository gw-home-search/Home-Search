package com.home.domain.complex.buildingmetadata;

import java.text.Normalizer;
import java.util.Locale;

public final class ComplexNameNormalizer {
	private ComplexNameNormalizer() {}

	public static String normalize(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		return Normalizer.normalize(value, Normalizer.Form.NFKC)
			.replaceAll("\\s+", "")
			.replaceAll("[()\\[\\]{}.,·\\-_/]", "")
			.toLowerCase(Locale.ROOT);
	}
}
