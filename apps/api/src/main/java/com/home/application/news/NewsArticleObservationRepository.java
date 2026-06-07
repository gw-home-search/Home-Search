package com.home.application.news;

public interface NewsArticleObservationRepository {

	boolean insertIfAbsent(NewsArticleObservationCommand command);
}
