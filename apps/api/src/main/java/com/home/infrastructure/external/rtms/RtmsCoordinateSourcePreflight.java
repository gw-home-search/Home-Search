package com.home.infrastructure.external.rtms;

@FunctionalInterface
public interface RtmsCoordinateSourcePreflight {

	void verify();

	static RtmsCoordinateSourcePreflight noop() {
		return () -> {
		};
	}
}
