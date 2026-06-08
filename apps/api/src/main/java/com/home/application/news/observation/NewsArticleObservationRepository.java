package com.home.application.news.observation;

public interface NewsArticleObservationRepository {

	boolean insertIfAbsent(NewsArticleObservationCommand command);
}
