package com.home.infrastructure.external.vworld;

import java.util.ArrayList;
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
	private static final int DEFAULT_MAX_ATTEMPTS = 2;
	private static final long DEFAULT_RETRY_BACKOFF_MILLIS = 100L;
	private static final int MAX_PAGES = 20;

	private final RestClient restClient;
	private final VworldParcelCoordinateProperties properties;
	private final int maxAttempts;
	private final long retryBackoffMillis;

	public VworldBuildingFootprintSource(RestClient restClient, VworldParcelCoordinateProperties properties) {
		this(restClient, properties, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETRY_BACKOFF_MILLIS);
	}

	VworldBuildingFootprintSource(
		RestClient restClient,
		VworldParcelCoordinateProperties properties,
		int maxAttempts,
		long retryBackoffMillis
	) {
		this.restClient = Objects.requireNonNull(restClient);
		this.properties = Objects.requireNonNull(properties);
		this.maxAttempts = Math.max(1, maxAttempts);
		this.retryBackoffMillis = Math.max(0L, retryBackoffMillis);
	}

	@Override
	public List<BuildingFootprintImportCandidate> fetchByPnu(String pnu) {
		String normalizedPnu = trimToNull(pnu);
		if (normalizedPnu == null || !properties.hasServiceKey()) {
			return List.of();
		}
		List<BuildingFootprintImportCandidate> results = new ArrayList<>();
		int pageNo = 1;
		int totalCount = properties.numOfRows();
		while (pageNo <= MAX_PAGES && (long) (pageNo - 1) * properties.numOfRows() < totalCount) {
			VworldBuildingFootprintResponse response = fetchPage(normalizedPnu, pageNo);
			if (response == null) {
				return results;
			}
			List<BuildingFootprintImportCandidate> page = response.footprints(normalizedPnu, SOURCE, SNAPSHOT_VERSION);
			results.addAll(page);
			totalCount = response.totalCountOr(results.size());
			if (page.isEmpty()) {
				break;
			}
			pageNo++;
		}
		return results;
	}

	private VworldBuildingFootprintResponse fetchPage(String normalizedPnu, int pageNo) {
		try {
			String query = "key=" + ExternalApiUri.serviceKeyQueryValue(properties.serviceKey())
				+ "&output=" + ExternalApiUri.queryValue("application/json")
				+ "&pnu=" + ExternalApiUri.queryValue(normalizedPnu)
				+ "&domain=" + ExternalApiUri.queryValue(properties.domain())
				+ "&srsName=" + ExternalApiUri.queryValue("EPSG:4326")
				+ "&pageNo=" + ExternalApiUri.queryValue(pageNo)
				+ "&numOfRows=" + ExternalApiUri.queryValue(properties.numOfRows());
			return getWithRetry(query);
		}
		catch (RestClientException exception) {
			return null;
		}
	}

	private VworldBuildingFootprintResponse getWithRetry(String query) {
		RestClientException lastException = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				return restClient.get()
					.uri(ExternalApiUri.create(properties.baseUrl(), properties.wfsPath(), query))
					.retrieve()
					.body(VworldBuildingFootprintResponse.class);
			}
			catch (RestClientException exception) {
				lastException = exception;
				if (attempt < maxAttempts) {
					backoff();
				}
			}
		}
		throw lastException;
	}

	private void backoff() {
		if (retryBackoffMillis == 0L) {
			return;
		}
		try {
			Thread.sleep(retryBackoffMillis);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted during VWorld footprint retry backoff", exception);
		}
	}

	private String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
