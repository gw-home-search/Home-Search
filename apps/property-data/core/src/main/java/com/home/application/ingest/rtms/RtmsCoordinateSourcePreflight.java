package com.home.application.ingest.rtms;

@FunctionalInterface
public interface RtmsCoordinateSourcePreflight {

	void verify();

	public static RtmsCoordinateSourcePreflight noop() {
		return () -> {
		};
	}
}
