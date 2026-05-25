package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SnapshotFirstParcelCoordinateResolverTest {

	@Test
	@DisplayName("coordinate snapshot은 VWorld fallback보다 먼저 사용된다")
	void snapshotCoordinateIsUsedBeforeVworldFallback() {
		ParcelCoordinate snapshot = new ParcelCoordinate(
			new BigDecimal("37.5012345"),
			new BigDecimal("127.0543210"),
			"MULTIPOLYGON(((127.0540 37.5010,127.0546 37.5010,127.0546 37.5015,127.0540 37.5015,127.0540 37.5010)))"
		);
		AtomicBoolean fallbackCalled = new AtomicBoolean(false);
		ParcelCoordinateResolver resolver = new SnapshotFirstParcelCoordinateResolver(
			pnu -> Optional.of(snapshot),
			(pnu, item) -> {
				fallbackCalled.set(true);
				return Optional.empty();
			}
		);

		Optional<ParcelCoordinate> coordinate = resolver.resolve("1168010300107770001", null);

		assertThat(coordinate).contains(snapshot);
		assertThat(fallbackCalled).isFalse();
	}

	@Test
	@DisplayName("VWorld fallback은 coordinate snapshot이 miss될 때만 사용된다")
	void vworldFallbackIsUsedWhenSnapshotMisses() {
		ParcelCoordinate fallback = new ParcelCoordinate(
			new BigDecimal("37.5012345"),
			new BigDecimal("127.0543210")
		);
		ParcelCoordinateResolver resolver = new SnapshotFirstParcelCoordinateResolver(
			pnu -> Optional.empty(),
			(pnu, item) -> Optional.of(fallback)
		);

		assertThat(resolver.resolve("1168010300107770001", null)).contains(fallback);
	}
}
