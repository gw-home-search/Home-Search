package com.home.infrastructure.external.odcloud;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.home.application.coordinate.identity.ComplexIdentityCandidate;
import com.home.application.coordinate.identity.ComplexIdentityCandidatePolicy;
import com.home.application.ingest.matching.ComplexIdentityResolver;
import com.home.application.ingest.trade.OpenApiTradeItem;
import com.home.infrastructure.external.ExternalApiUri;
import com.home.infrastructure.external.odcloud.dto.OdcloudAptResponse;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class OdcloudComplexIdentityResolver implements ComplexIdentityResolver {

	private final RestClient restClient;
	private final String baseUrl;
	private final String serviceKey;
	private final String aptPath;
	private final ComplexIdentityCandidatePolicy identityCandidatePolicy;

	public OdcloudComplexIdentityResolver(
		RestClient restClient,
		String baseUrl,
		String serviceKey,
		String aptPath
	) {
		this.restClient = Objects.requireNonNull(restClient);
		this.baseUrl = trimToNull(baseUrl);
		this.serviceKey = trimToNull(serviceKey);
		this.aptPath = Objects.requireNonNull(aptPath);
		this.identityCandidatePolicy = new ComplexIdentityCandidatePolicy();
	}

	@Override
	public Optional<String> resolvePnu(OpenApiTradeItem item) {
		String aptSeq = trimToNull(item.aptSeq());
		if (aptSeq == null || serviceKey == null) {
			return Optional.empty();
		}
		try {
			OdcloudAptResponse response = getBody(odcloudQuery(aptSeq));
			return identityCandidatePolicy.resolveUniquePnu(aptSeq, candidates(response));
		}
		catch (RestClientException exception) {
			return Optional.empty();
		}
	}

	private List<ComplexIdentityCandidate> candidates(OdcloudAptResponse response) {
		if (response == null || response.getData() == null) {
			return List.of();
		}
		return response.getData().stream()
			.filter(Objects::nonNull)
			.map(item -> new ComplexIdentityCandidate(item.getComplexPk(), item.getPnu()))
			.toList();
	}

	private OdcloudAptResponse getBody(String query) {
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

	private String odcloudQuery(String aptSeq) {
		return "page=" + ExternalApiUri.queryValue(1)
			+ "&perPage=" + ExternalApiUri.queryValue(20)
			+ "&cond%5BCOMPLEX_PK::EQ%5D=" + ExternalApiUri.queryValue(aptSeq)
			+ "&serviceKey=" + ExternalApiUri.serviceKeyQueryValue(serviceKey);
	}

	private String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
