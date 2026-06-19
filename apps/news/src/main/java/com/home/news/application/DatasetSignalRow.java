package com.home.news.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record DatasetSignalRow(
	String source,
	String sourceKey,
	String publisher,
	String title,
	String url,
	Instant firstSeenAt,
	LocalDate featureDateKst,
	String impactTarget,
	String impactDirection,
	String sentiment,
	BigDecimal confidence,
	String extractionVersion,
	String evidenceLevel
) {
}
