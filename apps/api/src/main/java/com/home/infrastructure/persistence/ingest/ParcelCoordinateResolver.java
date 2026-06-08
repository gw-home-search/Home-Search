package com.home.infrastructure.persistence.ingest;

import java.util.Optional;

import com.home.application.ingest.trade.OpenApiTradeItem;

@FunctionalInterface
public interface ParcelCoordinateResolver {

	Optional<ParcelCoordinate> resolve(String pnu, OpenApiTradeItem item);

	static ParcelCoordinateResolver empty() {
		return (pnu, item) -> Optional.empty();
	}
}
