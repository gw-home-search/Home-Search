package com.home.infrastructure.external.vworld;

import java.util.List;
import java.util.Objects;

import com.home.application.coordinate.BuildingFootprintImportCandidate;
import com.home.application.coordinate.BuildingFootprintSource;
import com.home.infrastructure.external.ExternalApiUri;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class VworldBuildingFootprintSource implements BuildingFootprintSource {

	private static final String SOURCE = "VWORLD_WFS";
	private static final String SNAPSHOT_VERSION = "LIVE";

	private final RestClient restClient;
	private final VworldParcelCoordinateProperties properties;

	public VworldBuildingFootprintSource(RestClient restClient, VworldParcelCoordinateProperties properties) {
		this.restClient = Objects.requireNonNull(restClient);
		this.properties = Objects.requireNonNull(properties);
	}

	@Override
	public List<BuildingFootprintImportCandidate> fetchByPnu(String pnu) {
		String normalizedPnu = trimToNull(pnu);
		if (normalizedPnu == null || !properties.hasServiceKey()) {
			return List.of();
		}
		try {
			String query = "key=" + ExternalApiUri.serviceKeyQueryValue(properties.serviceKey())
				+ "&output=" + ExternalApiUri.queryValue("application/json")
				+ "&pnu=" + ExternalApiUri.queryValue(normalizedPnu)
				+ "&domain=" + ExternalApiUri.queryValue(properties.domain())
				+ "&srsName=" + ExternalApiUri.queryValue("EPSG:4326")
				+ "&pageNo=" + ExternalApiUri.queryValue(1)
				+ "&numOfRows=" + ExternalApiUri.queryValue(properties.numOfRows());
			VworldBuildingFootprintResponse response = restClient.get()
				.uri(ExternalApiUri.create(properties.baseUrl(), properties.wfsPath(), query))
				.retrieve()
				.body(VworldBuildingFootprintResponse.class);
			return response == null ? List.of() : response.footprints(normalizedPnu, SOURCE, SNAPSHOT_VERSION);
		}
		catch (RestClientException exception) {
			return List.of();
		}
	}

	private String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
