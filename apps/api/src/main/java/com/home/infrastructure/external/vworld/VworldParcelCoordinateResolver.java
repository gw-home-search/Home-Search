package com.home.infrastructure.external.vworld;

import java.util.Objects;
import java.util.Optional;

import com.home.application.ingest.OpenApiTradeItem;
import com.home.infrastructure.persistence.ingest.ParcelCoordinate;
import com.home.infrastructure.persistence.ingest.ParcelCoordinateResolver;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class VworldParcelCoordinateResolver implements ParcelCoordinateResolver {

	private final RestClient restClient;
	private final VworldParcelCoordinateProperties properties;

	public VworldParcelCoordinateResolver(
		RestClient restClient,
		VworldParcelCoordinateProperties properties
	) {
		this.restClient = Objects.requireNonNull(restClient);
		this.properties = Objects.requireNonNull(properties);
	}

	@Override
	public Optional<ParcelCoordinate> resolve(String pnu, OpenApiTradeItem item) {
		String normalizedPnu = trimToNull(pnu);
		if (normalizedPnu == null || !properties.hasServiceKey()) {
			return Optional.empty();
		}
		try {
			VworldParcelCoordinateResponse response = restClient.get()
				.uri(uriBuilder -> uriBuilder
					.path(properties.wfsPath())
					.queryParam("key", properties.serviceKey())
					.queryParam("output", "application/json")
					.queryParam("pnu", normalizedPnu)
					.queryParam("domain", properties.domain())
					.queryParam("srsName", "EPSG:4326")
					.queryParam("pageNo", 1)
					.queryParam("numOfRows", properties.numOfRows())
					.build())
				.retrieve()
				.body(VworldParcelCoordinateResponse.class);
			return response != null ? response.center(normalizedPnu) : Optional.empty();
		}
		catch (RestClientException exception) {
			return Optional.empty();
		}
	}

	private String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
