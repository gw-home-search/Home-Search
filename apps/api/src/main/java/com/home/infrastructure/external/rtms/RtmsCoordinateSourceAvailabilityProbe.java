package com.home.infrastructure.external.rtms;

@FunctionalInterface
interface RtmsCoordinateSourceAvailabilityProbe {

	void verifyAvailable();
}
