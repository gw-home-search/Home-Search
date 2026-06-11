package com.home.infrastructure.scheduling.rtms;

@FunctionalInterface
interface RtmsDailyRefreshNotifier {

	void send(String message);

	static RtmsDailyRefreshNotifier noop() {
		return message -> {
		};
	}
}
