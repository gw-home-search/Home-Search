package com.home.infrastructure.external.complex;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;

import com.home.application.ingest.ComplexMetadata;
import com.home.application.ingest.ComplexMetadataEnrichmentClient;
import com.home.application.ingest.ComplexMetadataFailureKind;
import com.home.application.ingest.ComplexMetadataLookup;
import com.home.application.ingest.ComplexMetadataResolution;
import com.home.application.ingest.ComplexMetadataResolver;
import com.home.application.ingest.ComplexMetadataStatus;
import com.home.infrastructure.external.ExternalApiUri;
import com.home.infrastructure.external.apis.dto.ApisBldRecapResponse;
import com.home.infrastructure.external.odcloud.dto.OdcloudAptResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class PublicComplexMetadataResolver implements ComplexMetadataResolver, ComplexMetadataEnrichmentClient {

	private static final Logger log = LoggerFactory.getLogger(PublicComplexMetadataResolver.class);

	private final RestClient odcloudRestClient;
	private final String odcloudBaseUrl;
	private final String odcloudServiceKey;
	private final String odcloudAptPath;
	private final RestClient bldRestClient;
	private final String bldBaseUrl;
	private final String bldServiceKey;
	private final String bldRecapPath;
	private final String recapPath;
	private final boolean buildingFallbackEnabled;

	public PublicComplexMetadataResolver(
		RestClient odcloudRestClient,
		String odcloudServiceKey,
		String odcloudAptPath,
		RestClient bldRestClient,
		String bldServiceKey,
		String bldRecapPath,
		String recapPath
	) {
		this(odcloudRestClient, null, odcloudServiceKey, odcloudAptPath, bldRestClient, null, bldServiceKey,
			bldRecapPath, recapPath, true);
	}

	public PublicComplexMetadataResolver(
		RestClient odcloudRestClient,
		String odcloudBaseUrl,
		String odcloudServiceKey,
		String odcloudAptPath,
		RestClient bldRestClient,
		String bldBaseUrl,
		String bldServiceKey,
		String bldRecapPath,
		String recapPath,
		boolean buildingFallbackEnabled
	) {
		this.odcloudRestClient = Objects.requireNonNull(odcloudRestClient);
		this.odcloudBaseUrl = trimToNull(odcloudBaseUrl);
		this.odcloudServiceKey = trimToNull(odcloudServiceKey);
		this.odcloudAptPath = Objects.requireNonNull(odcloudAptPath);
		this.bldRestClient = Objects.requireNonNull(bldRestClient);
		this.bldBaseUrl = trimToNull(bldBaseUrl);
		this.bldServiceKey = trimToNull(bldServiceKey);
		this.bldRecapPath = Objects.requireNonNull(bldRecapPath);
		this.recapPath = Objects.requireNonNull(recapPath);
		this.buildingFallbackEnabled = buildingFallbackEnabled;
	}

	@Override
	public boolean isConfigured() {
		return odcloudServiceKey != null || (buildingFallbackEnabled && bldServiceKey != null);
	}

	@Override
	public ComplexMetadataResolution resolve(String pnu, String parcelAddress) {
		return resolve(new ComplexMetadataLookup(null, null, null, pnu, parcelAddress));
	}

	@Override
	public ComplexMetadataResolution resolve(ComplexMetadataLookup lookup) {
		ComplexMetadataResolution odcloud = resolveOdcloud(lookup.pnu(), lookup.parcelAddress());
		if (odcloud.status().isAmbiguous()) {
			return odcloud;
		}
		if (odcloud.status().isFailed()) {
			return odcloud;
		}
		if (hasMetadata(odcloud)) {
			if (!buildingFallbackEnabled) {
				return odcloud;
			}
			ComplexMetadataResolution building = resolveBuildingMetadata(lookup.pnu());
			if (hasMetadata(building)) {
				if (conflicts(odcloud.metadata(), building.metadata())) {
					return ComplexMetadataResolution.ambiguous("ODC+BLD", "complex metadata source conflict pnu=" + lookup.pnu());
				}
				return ComplexMetadataResolution.classify("ODC+BLD", merge(odcloud.metadata(), building.metadata()));
			}
			if (building.status().isAmbiguous()) {
				return odcloud;
			}
			return odcloud;
		}
		return buildingFallbackEnabled ? resolveBuildingMetadata(lookup.pnu()) : odcloud;
	}

	private ComplexMetadataResolution resolveOdcloud(String pnu, String parcelAddress) {
		if (odcloudServiceKey == null || trimToNull(parcelAddress) == null || trimToNull(pnu) == null) {
			return ComplexMetadataResolution.unavailable(
				"ODC",
				ComplexMetadataFailureKind.INPUT_INSUFFICIENT,
				"ODC lookup skipped"
			);
		}
		try {
			OdcloudAptResponse response = getBody(
				odcloudRestClient,
				odcloudBaseUrl,
				odcloudAptPath,
				odcloudQuery(parcelAddress),
				OdcloudAptResponse.class
			);
			if (response == null || response.getData() == null || response.getData().isEmpty()) {
				return ComplexMetadataResolution.unavailable("ODC", "ODC candidate unavailable");
			}
			List<OdcloudAptResponse.Item> matches = response.getData().stream()
				.filter(Objects::nonNull)
				.filter(item -> pnu.equals(trimToNull(item.getPnu())))
				.toList();
			if (matches.size() > 1) {
				return ComplexMetadataResolution.ambiguous("ODC", "ODC PNU candidate ambiguous pnu=" + pnu);
			}
			if (matches.isEmpty()) {
				return ComplexMetadataResolution.unavailable("ODC", "ODC exact PNU candidate unavailable pnu=" + pnu);
			}
			OdcloudAptResponse.Item selected = matches.get(0);
			return ComplexMetadataResolution.classify("ODC", new ComplexMetadata(
				selected.getDongCnt(),
				selected.getUnitCnt(),
				null,
				null,
				null,
				null,
				null,
				parseUseDate(selected.getUseaprDt())
			));
		}
		catch (RestClientException exception) {
			log.warn("ODC complex metadata lookup failed pnu={} address={}", pnu, parcelAddress, exception);
			return ComplexMetadataResolution.failed(
				"ODC",
				ComplexMetadataFailureKind.TRANSIENT,
				redactSensitive(exception.getMessage())
			);
		}
	}

	private ComplexMetadataResolution resolveBuildingMetadata(String pnu) {
		if (bldServiceKey == null || pnu == null || pnu.length() < 19) {
			return ComplexMetadataResolution.unavailable(
				"BLD",
				ComplexMetadataFailureKind.INPUT_INSUFFICIENT,
				"building metadata lookup skipped"
			);
		}
		try {
			ComplexMetadataResolution recap = fetchBuildingMetadata(bldRecapPath, pnu);
			if (!recap.status().isUnavailable()) {
				return recap;
			}
			return fetchBuildingMetadata(recapPath, pnu);
		}
		catch (RestClientException exception) {
			log.warn("Building complex metadata lookup failed pnu={}", pnu, exception);
			return ComplexMetadataResolution.failed(
				"BLD",
				ComplexMetadataFailureKind.TRANSIENT,
				redactSensitive(exception.getMessage())
			);
		}
	}

	private ComplexMetadataResolution fetchBuildingMetadata(String path, String pnu) {
		ApisBldRecapResponse response = getBody(
			bldRestClient,
			bldBaseUrl,
			path,
			buildingQuery(pnu),
			ApisBldRecapResponse.class
		);
		if (response == null || response.getResponse() == null || response.getResponse().getBody() == null
			|| response.getResponse().getBody().getItems() == null
			|| response.getResponse().getBody().getItems().getItem() == null
			|| response.getResponse().getBody().getItems().getItem().isEmpty()) {
			return ComplexMetadataResolution.unavailable("BLD", "building metadata candidate unavailable");
		}
		List<ApisBldRecapResponse.Item> apartmentItems = response.getResponse().getBody().getItems().getItem()
			.stream()
			.filter(Objects::nonNull)
			.filter(item -> "02000".equals(item.getMainPurpsCd()))
			.toList();
		if (apartmentItems.size() > 1) {
			return ComplexMetadataResolution.ambiguous("BLD", "building apartment candidate ambiguous pnu=" + pnu);
		}
		if (apartmentItems.isEmpty()) {
			return ComplexMetadataResolution.unavailable("BLD", "building apartment candidate unavailable");
		}
		ApisBldRecapResponse.Item item = apartmentItems.get(0);
		return ComplexMetadataResolution.classify("BLD", new ComplexMetadata(
			null,
			item.getHhldCnt(),
			bd(item.getPlatArea()),
			bd(item.getArchArea()),
			bd(item.getTotArea()),
			bd(item.getBcRat()),
			bd(item.getVlRat()),
			null
		));
	}

	private boolean hasMetadata(ComplexMetadataResolution resolution) {
		return resolution.status().isResolvedLike();
	}

	private <T> T getBody(RestClient restClient, String baseUrl, String path, String query, Class<T> bodyType) {
		if (baseUrl != null) {
			return restClient.get()
				.uri(ExternalApiUri.create(baseUrl, path, query))
				.retrieve()
				.body(bodyType);
		}
		String normalizedPath = path.startsWith("/") ? path : "/" + path;
		return restClient.get()
			.uri(normalizedPath + "?" + query)
			.retrieve()
			.body(bodyType);
	}

	private String odcloudQuery(String parcelAddress) {
		return "page=" + ExternalApiUri.queryValue(1)
			+ "&perPage=" + ExternalApiUri.queryValue(20)
			+ "&cond%5BADRES::LIKE%5D=" + ExternalApiUri.queryValue(parcelAddress)
			+ "&serviceKey=" + ExternalApiUri.serviceKeyQueryValue(odcloudServiceKey);
	}

	private String buildingQuery(String pnu) {
		String sigunguCd = pnu.substring(0, 5);
		String bjdongCd = pnu.substring(5, 10);
		String bun = pnu.substring(11, 15);
		String ji = pnu.substring(15, 19);
		return "_type=" + ExternalApiUri.queryValue("json")
			+ "&serviceKey=" + ExternalApiUri.serviceKeyQueryValue(bldServiceKey)
			+ "&sigunguCd=" + ExternalApiUri.queryValue(sigunguCd)
			+ "&bjdongCd=" + ExternalApiUri.queryValue(bjdongCd)
			+ "&bun=" + ExternalApiUri.queryValue(bun)
			+ "&ji=" + ExternalApiUri.queryValue(ji);
	}

	private ComplexMetadata merge(ComplexMetadata odcloudMetadata, ComplexMetadata buildingMetadata) {
		if (odcloudMetadata == null) {
			return buildingMetadata;
		}
		if (buildingMetadata == null) {
			return odcloudMetadata;
		}
		return new ComplexMetadata(
			firstNonNull(odcloudMetadata.dongCnt(), buildingMetadata.dongCnt()),
			firstNonNull(odcloudMetadata.unitCnt(), buildingMetadata.unitCnt()),
			firstNonNull(odcloudMetadata.platArea(), buildingMetadata.platArea()),
			firstNonNull(odcloudMetadata.archArea(), buildingMetadata.archArea()),
			firstNonNull(odcloudMetadata.totArea(), buildingMetadata.totArea()),
			firstNonNull(odcloudMetadata.bcRat(), buildingMetadata.bcRat()),
			firstNonNull(odcloudMetadata.vlRat(), buildingMetadata.vlRat()),
			firstNonNull(odcloudMetadata.useDate(), buildingMetadata.useDate())
		);
	}

	private boolean conflicts(ComplexMetadata first, ComplexMetadata second) {
		if (first == null || second == null) {
			return false;
		}
		return conflict(first.dongCnt(), second.dongCnt())
			|| conflict(first.unitCnt(), second.unitCnt())
			|| conflict(first.platArea(), second.platArea())
			|| conflict(first.archArea(), second.archArea())
			|| conflict(first.totArea(), second.totArea())
			|| conflict(first.bcRat(), second.bcRat())
			|| conflict(first.vlRat(), second.vlRat())
			|| conflict(first.useDate(), second.useDate());
	}

	private boolean conflict(Object first, Object second) {
		if (first instanceof BigDecimal firstNumber && second instanceof BigDecimal secondNumber) {
			return firstNumber.compareTo(secondNumber) != 0;
		}
		return first != null && second != null && !first.equals(second);
	}

	private BigDecimal bd(Double value) {
		if (value == null) {
			return null;
		}
		String text = trimToNull(value.toString());
		if (text == null) {
			return null;
		}
		try {
			return new BigDecimal(text);
		}
		catch (NumberFormatException exception) {
			return null;
		}
	}

	private LocalDate parseUseDate(String value) {
		String text = trimToNull(value);
		if (text == null) {
			return null;
		}
		String normalized = text.replace("-", "").replace("/", "").replace(".", "");
		try {
			if (normalized.length() == 8) {
				return LocalDate.parse(normalized, DateTimeFormatter.BASIC_ISO_DATE);
			}
			return LocalDate.parse(text);
		}
		catch (DateTimeParseException exception) {
			return null;
		}
	}

	private String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}

	private String redactSensitive(String message) {
		if (message == null) {
			return null;
		}
		return message.replaceAll("(?i)(serviceKey=)[^&\\s]+", "$1[REDACTED]");
	}

	private <T> T firstNonNull(T first, T second) {
		return first != null ? first : second;
	}
}
