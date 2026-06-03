package com.home.infrastructure.external.rtms;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RequiredRtmsCoordinateSourcePreflight implements RtmsCoordinateSourcePreflight {

	private static final Logger log = LoggerFactory.getLogger(RequiredRtmsCoordinateSourcePreflight.class);

	private final String jdbcUrl;
	private final boolean allowCoordinatePendingOnly;
	private final RtmsCoordinateSourceAvailabilityProbe availabilityProbe;

	RequiredRtmsCoordinateSourcePreflight(
		String jdbcUrl,
		boolean allowCoordinatePendingOnly,
		RtmsCoordinateSourceAvailabilityProbe availabilityProbe
	) {
		this.jdbcUrl = jdbcUrl;
		this.allowCoordinatePendingOnly = allowCoordinatePendingOnly;
		this.availabilityProbe = Objects.requireNonNull(availabilityProbe);
	}

	@Override
	public void verify() {
		if (allowCoordinatePendingOnly) {
			log.warn(
				"RTMS coordinate source preflight bypassed; coordinate-pending parcels can be stored but public "
					+ "map markers will not appear until approved coordinates are supplied"
			);
			return;
		}
		if (jdbcUrl == null || jdbcUrl.isBlank()) {
			throw new IllegalStateException(
				"COORDINATE_SOURCE_DB_JDBC_URL is required for RTMS ingest because coordinate-pending rows are "
					+ "storage-safe but not marker-safe. Set HOME_INGEST_RTMS_ALLOW_COORDINATE_PENDING_ONLY=true "
					+ "only for storage-only experiments."
			);
		}
		availabilityProbe.verifyAvailable();
	}
}
