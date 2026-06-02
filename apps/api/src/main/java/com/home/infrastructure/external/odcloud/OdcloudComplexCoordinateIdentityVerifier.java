package com.home.infrastructure.external.odcloud;

import java.util.List;
import java.util.Objects;

import com.home.application.coordinate.ComplexCoordinateIdentityVerification;
import com.home.application.coordinate.ComplexCoordinateIdentityVerifier;
import com.home.application.coordinate.ComplexCoordinateParcelTargets;
import com.home.application.coordinate.ComplexCoordinateTarget;
import com.home.infrastructure.external.ExternalApiUri;
import com.home.infrastructure.external.odcloud.dto.OdcloudAptResponse;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class OdcloudComplexCoordinateIdentityVerifier implements ComplexCoordinateIdentityVerifier {

	private static final int DEFAULT_MAX_ATTEMPTS = 2;
	private static final long DEFAULT_RETRY_BACKOFF_MILLIS = 100L;

	private final RestClient restClient;
	private final String baseUrl;
	private final String serviceKey;
	private final String aptPath;
	private final int maxAttempts;
	private final long retryBackoffMillis;

	public OdcloudComplexCoordinateIdentityVerifier(
		RestClient restClient,
		String baseUrl,
		String serviceKey,
		String aptPath
	) {
		this(restClient, baseUrl, serviceKey, aptPath, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETRY_BACKOFF_MILLIS);
	}

	OdcloudComplexCoordinateIdentityVerifier(
		RestClient restClient,
		String baseUrl,
		String serviceKey,
		String aptPath,
		int maxAttempts,
		long retryBackoffMillis
	) {
		this.restClient = Objects.requireNonNull(restClient);
		this.baseUrl = trimToNull(baseUrl);
		this.serviceKey = trimToNull(serviceKey);
		this.aptPath = Objects.requireNonNull(aptPath);
		this.maxAttempts = Math.max(1, maxAttempts);
		this.retryBackoffMillis = Math.max(0L, retryBackoffMillis);
	}

	@Override
	public ComplexCoordinateIdentityVerification verify(
		ComplexCoordinateParcelTargets parcelTargets,
		ComplexCoordinateTarget target
	) {
		Objects.requireNonNull(parcelTargets, "parcelTargets is required");
		Objects.requireNonNull(target, "target is required");
		String aptSeq = trimToNull(target.aptSeq());
		String pnu = trimToNull(parcelTargets.pnu());
		if (aptSeq == null || pnu == null || serviceKey == null) {
			return ComplexCoordinateIdentityVerification.unavailable("ODC identity lookup skipped");
		}
		try {
			OdcloudAptResponse response = getBody(odcloudQuery(aptSeq));
			if (response == null || response.getData() == null || response.getData().isEmpty()) {
				return ComplexCoordinateIdentityVerification.unavailable("ODC identity candidate unavailable");
			}
			List<String> pnus = response.getData().stream()
				.filter(Objects::nonNull)
				.filter(candidate -> aptSeq.equals(trimToNull(candidate.getComplexPk())))
				.map(OdcloudAptResponse.Item::getPnu)
				.map(this::trimToNull)
				.filter(this::validPnu)
				.distinct()
				.toList();
			if (pnus.isEmpty()) {
				return ComplexCoordinateIdentityVerification.unavailable("ODC exact COMPLEX_PK candidate unavailable");
			}
			if (pnus.size() > 1) {
				return ComplexCoordinateIdentityVerification.ambiguous("ODC exact COMPLEX_PK has multiple PNU candidates");
			}
			if (!pnu.equals(pnus.get(0))) {
				return ComplexCoordinateIdentityVerification.ambiguous("ODC PNU conflicts with parcel PNU");
			}
			return ComplexCoordinateIdentityVerification.confirmed("ODC aptSeq/PNU identity confirmed");
		}
		catch (RestClientException exception) {
			return ComplexCoordinateIdentityVerification.failed("ODC identity lookup failed");
		}
	}

	private OdcloudAptResponse getBody(String query) {
		RestClientException lastException = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				return getBodyOnce(query);
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

	private OdcloudAptResponse getBodyOnce(String query) {
		if (baseUrl != null) {
			return restClient.get()
				.uri(ExternalApiUri.create(baseUrl, aptPath, query))
				.retrieve()
				.body(OdcloudAptResponse.class);
		}
		String normalizedPath = aptPath.startsWith("/") ? aptPath : "/" + aptPath;
		return restClient.get()
			.uri(normalizedPath + "?" + query)
			.retrieve()
			.body(OdcloudAptResponse.class);
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
			throw new IllegalStateException("Interrupted during ODC identity retry backoff", exception);
		}
	}

	private String odcloudQuery(String aptSeq) {
		return "page=" + ExternalApiUri.queryValue(1)
			+ "&perPage=" + ExternalApiUri.queryValue(20)
			+ "&cond%5BCOMPLEX_PK::EQ%5D=" + ExternalApiUri.queryValue(aptSeq)
			+ "&serviceKey=" + ExternalApiUri.serviceKeyQueryValue(serviceKey);
	}

	private boolean validPnu(String value) {
		return value != null && value.matches("\\d{19}");
	}

	private String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
