package com.home.infrastructure.scheduling.rtms;

@FunctionalInterface
interface RtmsCoordinateSourcePreflight {

	void verify();

	static RtmsCoordinateSourcePreflight noop() {
		return () -> {
		};
	}
}
