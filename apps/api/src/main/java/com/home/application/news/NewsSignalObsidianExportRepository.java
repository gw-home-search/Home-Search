package com.home.application.news;

import java.time.OffsetDateTime;
import java.util.List;

public interface NewsSignalObsidianExportRepository {

	List<NewsSignalDatasetRow> findObservedBetween(
		OffsetDateTime startInclusive,
		OffsetDateTime endExclusive,
		int limit
	);
}
