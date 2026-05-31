package com.home.infrastructure.external.complex;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;

import com.home.application.ingest.ComplexMetadata;
import com.home.application.ingest.ComplexMetadataResolver;
import com.home.application.ingest.OpenApiTradeItem;
import com.home.infrastructure.external.apis.dto.ApisBldRecapResponse;
import com.home.infrastructure.external.odcloud.dto.OdcloudAptResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class PublicComplexMetadataResolver implements ComplexMetadataResolver {

	private static final Logger log = LoggerFactory.getLogger(PublicComplexMetadataResolver.class);

	private final RestClient odcloudRestClient;
	private final String odcloudServiceKey;
	private final String odcloudAptPath;
	private final RestClient bldRestClient;
	private final String bldServiceKey;
	private final String bldRecapPath;
	private final String recapPath;

	public PublicComplexMetadataResolver(
		RestClient odcloudRestClient,
		String odcloudServiceKey,
		String odcloudAptPath,
		RestClient bldRestClient,
		String bldServiceKey,
		String bldRecapPath,
		String recapPath
	) {
		this.odcloudRestClient = Objects.requireNonNull(odcloudRestClient);
		this.odcloudServiceKey = trimToNull(odcloudServiceKey);
		this.odcloudAptPath = Objects.requireNonNull(odcloudAptPath);
		this.bldRestClient = Objects.requireNonNull(bldRestClient);
		this.bldServiceKey = trimToNull(bldServiceKey);
		this.bldRecapPath = Objects.requireNonNull(bldRecapPath);
		this.recapPath = Objects.requireNonNull(recapPath);
	}

	@Override
	public Optional<ComplexMetadata> resolve(OpenApiTradeItem item, String pnu, String parcelAddress) {
		ComplexMetadata odcloudMetadata = resolveOdcloud(item, pnu, parcelAddress);
		ComplexMetadata bldMetadata = resolveBuildingMetadata(pnu);
		ComplexMetadata merged = merge(odcloudMetadata, bldMetadata);
		if (merged == null || !hasAnyValue(merged)) {
			return Optional.empty();
		}
		return Optional.of(merged);
	}

	private ComplexMetadata resolveOdcloud(OpenApiTradeItem item, String pnu, String parcelAddress) {
		if (odcloudServiceKey == null || parcelAddress == null) {
			return ComplexMetadata.empty();
		}
		try {
			OdcloudAptResponse response = odcloudRestClient.get()
				.uri(uriBuilder -> uriBuilder
					.path(odcloudAptPath)
					.queryParam("page", 1)
					.queryParam("perPage", 20)
					.queryParam("cond[ADRES::LIKE]", parcelAddress)
					.queryParam("serviceKey", odcloudServiceKey)
					.build())
				.retrieve()
				.body(OdcloudAptResponse.class);
			if (response == null || response.getData() == null || response.getData().isEmpty()) {
				return ComplexMetadata.empty();
			}
			OdcloudAptResponse.Item selected = selectOdcloudCandidate(response, pnu);
			if (selected == null) {
				return ComplexMetadata.empty();
			}
			Integer dongCnt = selected.getDongCnt();
			Integer unitCnt = selected.getUnitCnt();
			LocalDate useDate = parseUseDate(selected.getUseaprDt());
			return new ComplexMetadata(
				dongCnt,
				unitCnt,
				null,
				null,
				null,
				null,
				null,
				useDate
			);
		}
		catch (RestClientException exception) {
			log.warn("ODC complex metadata lookup failed pnu={} address={}", pnu, parcelAddress, exception);
			return ComplexMetadata.empty();
		}
	}

	private ComplexMetadata resolveBuildingMetadata(String pnu) {
		if (bldServiceKey == null || pnu == null || pnu.length() < 19) {
			return ComplexMetadata.empty();
		}
		try {
			ComplexMetadata recap = fetchBuildingMetadata(bldRecapPath, pnu);
			if (hasAnyValue(recap)) {
				return recap;
			}
			return fetchBuildingMetadata(recapPath, pnu);
		}
		catch (RestClientException exception) {
			log.warn("Building complex metadata lookup failed pnu={}", pnu, exception);
			return ComplexMetadata.empty();
		}
	}

	private ComplexMetadata fetchBuildingMetadata(String path, String pnu) {
		String sigunguCd = pnu.substring(0, 5);
		String bjdongCd = pnu.substring(5, 10);
		String bun = pnu.substring(11, 15);
		String ji = pnu.substring(15, 19);
		try {
			ApisBldRecapResponse response = bldRestClient.get()
				.uri(uriBuilder -> uriBuilder
					.path(path)
					.queryParam("_type", "json")
					.queryParam("serviceKey", bldServiceKey)
					.queryParam("sigunguCd", sigunguCd)
					.queryParam("bjdongCd", bjdongCd)
					.queryParam("bun", bun)
					.queryParam("ji", ji)
					.build())
				.retrieve()
				.body(ApisBldRecapResponse.class);
			if (response == null || response.getResponse() == null || response.getResponse().getBody() == null
				|| response.getResponse().getBody().getItems() == null
				|| response.getResponse().getBody().getItems().getItem() == null
				|| response.getResponse().getBody().getItems().getItem().isEmpty()) {
				return ComplexMetadata.empty();
			}
			for (ApisBldRecapResponse.Item item : response.getResponse().getBody().getItems().getItem()) {
				if (!"02000".equals(item.getMainPurpsCd())) {
					continue;
				}
				Integer dongCnt = item.getHhldCnt();
				return new ComplexMetadata(
					null,
					dongCnt,
					bd(item.getPlatArea()),
					bd(item.getArchArea()),
					bd(item.getTotArea()),
					bd(item.getBcRat()),
					bd(item.getVlRat()),
					null
				);
			}
			return ComplexMetadata.empty();
		}
		catch (RestClientException exception) {
			log.warn("Building complex metadata lookup failed path={} pnu={}", path, pnu, exception);
			return ComplexMetadata.empty();
		}
	}

	private OdcloudAptResponse.Item selectOdcloudCandidate(OdcloudAptResponse response, String pnu) {
		if (response != null && response.getData() != null && pnu != null) {
			for (OdcloudAptResponse.Item item : response.getData()) {
				if (pnu.equals(item.getPnu())) {
					return item;
				}
			}
		}
		return response != null && response.getData() != null && response.getData().size() == 1
			? response.getData().get(0)
			: null;
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

	private boolean hasAnyValue(ComplexMetadata metadata) {
		return metadata != null && (
			metadata.dongCnt() != null
				|| metadata.unitCnt() != null
				|| metadata.platArea() != null
				|| metadata.archArea() != null
				|| metadata.totArea() != null
				|| metadata.bcRat() != null
				|| metadata.vlRat() != null
				|| metadata.useDate() != null
		);
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

	private <T> T firstNonNull(T first, T second) {
		return first != null ? first : second;
	}
}
