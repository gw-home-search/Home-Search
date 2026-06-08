package com.home.application.news.export;

import java.time.OffsetDateTime;
import java.util.List;
import com.home.application.news.signal.NewsSignalDatasetRow;

public interface NewsSignalObsidianExportRepository {

	List<NewsSignalDatasetRow> findObservedBetween(
		OffsetDateTime startInclusive,
		OffsetDateTime endExclusive,
		int limit
	);
}
