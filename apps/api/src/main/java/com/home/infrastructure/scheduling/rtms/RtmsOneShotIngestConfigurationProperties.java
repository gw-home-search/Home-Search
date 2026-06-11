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
	Boolean allowCoordinatePendingOnly,
	Nationwide nationwide
) {

	RtmsOneShotIngestProperties toProperties() {
		Nationwide nationwideProperties = nationwide == null ? Nationwide.defaults() : nationwide;
		return new RtmsOneShotIngestProperties(
			Boolean.TRUE.equals(enabled),
			blankToDefault(lawdCd, ""),
			blankToDefault(dealYmd, ""),
			pageNo == null ? 1 : pageNo,
			Boolean.TRUE.equals(preflightOnly),
			blankToDefault(mode, "one-shot"),
			lookbackMonths == null ? 0 : lookbackMonths,
			Boolean.TRUE.equals(allowCoordinatePendingOnly),
			blankToDefault(nationwideProperties.lawdCds(), ""),
			blankToDefault(nationwideProperties.dealYmdFrom(), "201201"),
			blankToDefault(nationwideProperties.dealYmdTo(), "202606"),
			blankToDefault(nationwideProperties.jobKey(), ""),
			blankToDefault(nationwideProperties.workerId(), "rtms-backfill-worker"),
			nationwideProperties.leaseMinutes() == null ? 30 : nationwideProperties.leaseMinutes(),
			nationwideProperties.maxAttemptCount() == null ? 3 : nationwideProperties.maxAttemptCount(),
			nationwideProperties.chunkLimit() == null ? Integer.MAX_VALUE : nationwideProperties.chunkLimit()
		);
	}

	private static String blankToDefault(String value, String defaultValue) {
		return value == null || value.isBlank() ? defaultValue : value.trim();
	}

	record Nationwide(
		String lawdCds,
		String dealYmdFrom,
		String dealYmdTo,
		String jobKey,
		String workerId,
		Integer leaseMinutes,
		Integer maxAttemptCount,
		Integer chunkLimit
	) {

		private static Nationwide defaults() {
			return new Nationwide(null, null, null, null, null, null, null, null);
		}
	}
}
