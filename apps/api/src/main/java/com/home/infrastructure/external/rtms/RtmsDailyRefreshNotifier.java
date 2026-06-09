package com.home.infrastructure.external.rtms;

@FunctionalInterface
interface RtmsDailyRefreshNotifier {

	void send(String message);

	static RtmsDailyRefreshNotifier noop() {
		return message -> {
		};
	}
}
