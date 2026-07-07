package com.home.infrastructure.external.rtms;

public interface RtmsCoordinateSourceAvailabilityProbe {

	boolean configured();

	void verifyAvailable();
}
