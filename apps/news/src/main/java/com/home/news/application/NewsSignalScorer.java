package com.home.news.application;

public interface NewsSignalScorer {

	NewsSignalExtraction score(ArticleObservationResult observation);
}
