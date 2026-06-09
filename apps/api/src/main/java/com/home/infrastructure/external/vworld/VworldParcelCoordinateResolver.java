package com.home.infrastructure.external.vworld;

import java.util.Objects;
import java.util.Optional;

import com.home.application.coordinate.lookup.ParcelCoordinate;
import com.home.application.coordinate.lookup.ParcelCoordinateResolver;
import com.home.infrastructure.external.ExternalApiUri;

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
	public Optional<ParcelCoordinate> resolve(String pnu) {
		String normalizedPnu = trimToNull(pnu);
		if (normalizedPnu == null || !properties.hasServiceKey()) {
			return Optional.empty();
		}
		try {
			String query = "key=" + ExternalApiUri.serviceKeyQueryValue(properties.serviceKey())
				+ "&output=" + ExternalApiUri.queryValue("application/json")
				+ "&pnu=" + ExternalApiUri.queryValue(normalizedPnu)
				+ "&domain=" + ExternalApiUri.queryValue(properties.domain())
				+ "&srsName=" + ExternalApiUri.queryValue("EPSG:4326")
				+ "&pageNo=" + ExternalApiUri.queryValue(1)
				+ "&numOfRows=" + ExternalApiUri.queryValue(properties.numOfRows());
			VworldParcelCoordinateResponse response = restClient.get()
				.uri(ExternalApiUri.create(properties.baseUrl(), properties.wfsPath(), query))
				.retrieve()
				.body(VworldParcelCoordinateResponse.class);
			return response != null
				? response.center(normalizedPnu)
					.map(center -> new ParcelCoordinate(center.latitude(), center.longitude()))
				: Optional.empty();
		}
		catch (RestClientException exception) {
			return Optional.empty();
		}
	}

	private String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
