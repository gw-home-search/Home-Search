package com.home.infrastructure.persistence.ingest;

import java.util.Optional;

final class RtmsParcelAddressFormatter {

	private RtmsParcelAddressFormatter() {
	}

	static Optional<String> format(String regionName, String pnu) {
		String name = trimToNull(regionName);
		if (name == null || pnu == null || !pnu.matches("\\d{19}")) {
			return Optional.empty();
		}
		String bun = stripLeadingZeros(pnu.substring(11, 15));
		if (bun == null) {
			return Optional.empty();
		}
		String ji = stripLeadingZeros(pnu.substring(15, 19));
		String lotNumber = "2".equals(pnu.substring(10, 11)) ? "산 " + bun : bun;
		if (ji != null) {
			lotNumber = lotNumber + "-" + ji;
		}
		return Optional.of(name + " " + lotNumber);
	}

	private static String stripLeadingZeros(String value) {
		String stripped = value.replaceFirst("^0+", "");
		return stripped.isBlank() ? null : stripped;
	}

	private static String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
