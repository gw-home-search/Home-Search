package com.home.infrastructure.scheduling.news;

interface NaverNewsDailyPipelineNotifier {

	void send(String message);

	default boolean requested() {
		return true;
	}

	static NaverNewsDailyPipelineNotifier noop() {
		return new NaverNewsDailyPipelineNotifier() {

			@Override
			public void send(String message) {
			}

			@Override
			public boolean requested() {
				return false;
			}
		};
	}
}
