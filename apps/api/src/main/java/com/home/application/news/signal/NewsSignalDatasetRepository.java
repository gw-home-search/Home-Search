package com.home.application.news.signal;

import java.time.OffsetDateTime;
import java.util.List;

public interface NewsSignalDatasetRepository {

	List<NewsSignalDatasetRow> findAtOrBefore(OffsetDateTime predictionCutoff, int limit);
}
