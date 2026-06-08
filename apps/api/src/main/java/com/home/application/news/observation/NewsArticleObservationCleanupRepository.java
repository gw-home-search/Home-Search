package com.home.application.news.observation;

import java.time.OffsetDateTime;
import java.util.List;

public interface NewsArticleObservationCleanupRepository {

	List<NewsArticleObservationCleanupRecord> purgeProviderPayloads(OffsetDateTime retentionCutoff);
}
