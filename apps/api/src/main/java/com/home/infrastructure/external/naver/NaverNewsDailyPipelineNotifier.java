package com.home.infrastructure.external.naver;

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
