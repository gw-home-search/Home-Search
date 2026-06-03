package com.home.infrastructure.external.rtms;

@FunctionalInterface
interface RtmsCoordinateSourcePreflight {

	void verify();

	static RtmsCoordinateSourcePreflight noop() {
		return () -> {
		};
	}
}
