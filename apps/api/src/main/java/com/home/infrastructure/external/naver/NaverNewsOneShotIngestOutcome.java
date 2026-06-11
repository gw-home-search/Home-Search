package com.home.infrastructure.external.naver;

import com.home.application.news.observation.NewsArticleObservationDetailedIngestResult;

public record NaverNewsOneShotIngestOutcome(
	NewsArticleObservationDetailedIngestResult detailedResult
) {
}
