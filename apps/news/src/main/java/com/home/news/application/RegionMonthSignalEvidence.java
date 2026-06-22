package com.home.news.application;

import java.time.LocalDate;
import java.util.List;

import com.home.domain.news.RegionMonthSignalEvidenceScope;

public record RegionMonthSignalEvidence(
	String sourceKey,
	String title,
	String publisher,
	LocalDate publishedDate,
	String url,
	String citationUrl,
	List<String> topicTags,
	RegionMonthSignalEvidenceScope evidenceScope
) {
}
