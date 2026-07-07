package com.home.application.coordinate.lookup;

import java.util.Optional;

@FunctionalInterface
public interface ParcelCoordinateResolver {

	Optional<ParcelCoordinate> resolve(String pnu);

	static ParcelCoordinateResolver empty() {
		return pnu -> Optional.empty();
	}
}
