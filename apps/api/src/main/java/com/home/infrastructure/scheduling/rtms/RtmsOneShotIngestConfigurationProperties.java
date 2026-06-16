package com.home.infrastructure.scheduling.rtms;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "home.ingest.rtms")
record RtmsOneShotIngestConfigurationProperties(
	Boolean enabled,
	String lawdCd,
	String dealYmd,
	Integer pageNo,
	Boolean preflightOnly,
	String mode,
	Integer lookbackMonths,
	Boolean allowCoordinatePendingOnly
) {

	RtmsOneShotIngestProperties toProperties() {
		return new RtmsOneShotIngestProperties(
			Boolean.TRUE.equals(enabled),
			blankToDefault(lawdCd, ""),
			blankToDefault(dealYmd, ""),
			pageNo == null ? 1 : pageNo,
			Boolean.TRUE.equals(preflightOnly),
			blankToDefault(mode, "one-shot"),
			lookbackMonths == null ? 0 : lookbackMonths,
			Boolean.TRUE.equals(allowCoordinatePendingOnly)
		);
	}

	private static String blankToDefault(String value, String defaultValue) {
		return value == null || value.isBlank() ? defaultValue : value.trim();
	}

}
