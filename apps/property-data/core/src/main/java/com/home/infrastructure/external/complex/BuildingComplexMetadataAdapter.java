package com.home.infrastructure.external.complex;

import com.home.application.ingest.metadata.ComplexMetadata;
import com.home.application.ingest.metadata.ComplexMetadataLookupEvidence;
import com.home.application.ingest.metadata.ComplexMetadataResolution;
import com.home.domain.complex.metadata.ComplexMetadataFailureKind;
import com.home.domain.complex.metadata.ComplexMetadataLookupPath;
import com.home.infrastructure.external.ExternalApiUri;
import com.home.infrastructure.external.apis.dto.ApisBldRecapResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

final class BuildingComplexMetadataAdapter {

    private static final Logger log = LoggerFactory.getLogger(BuildingComplexMetadataAdapter.class);

    private final RestClient restClient;
    private final String baseUrl;
    private final String serviceKey;
    private final String recapTitlePath;
    private final String titlePath;

    BuildingComplexMetadataAdapter(
            RestClient restClient, String baseUrl, String serviceKey, String recapTitlePath, String titlePath) {
        this.restClient = Objects.requireNonNull(restClient);
        this.baseUrl = trimToNull(baseUrl);
        this.serviceKey = trimToNull(serviceKey);
        this.recapTitlePath = Objects.requireNonNull(recapTitlePath);
        this.titlePath = Objects.requireNonNull(titlePath);
    }

    boolean isConfigured() {
        return serviceKey != null;
    }

    ComplexMetadataResolution resolve(String pnu) {
        if (!isConfigured() || pnu == null || pnu.length() < 19) {
            return ComplexMetadataResolution.unavailable(
                    "BLD", ComplexMetadataFailureKind.INPUT_INSUFFICIENT, "building metadata lookup skipped");
        }
        try {
            ComplexMetadataResolution recap = fetch(recapTitlePath, pnu);
            ComplexMetadataResolution resolved = recap.status().isUnavailable() ? fetch(titlePath, pnu) : recap;
            return resolved.withLookupEvidence(evidence(pnu, pnu));
        } catch (RestClientException exception) {
            log.warn(
                    "Building complex metadata lookup failed pnu={} errorType={}",
                    pnu,
                    exception.getClass().getSimpleName());
            return ComplexMetadataResolution.failed(
                            "BLD", ComplexMetadataFailureKind.TRANSIENT, redactSensitive(exception.getMessage()))
                    .withLookupEvidence(evidence(pnu, null));
        }
    }

    private ComplexMetadataResolution fetch(String path, String pnu) {
        ApisBldRecapResponse response = getBody(path, buildingQuery(pnu));
        if (response == null
                || response.getResponse() == null
                || response.getResponse().getBody() == null
                || response.getResponse().getBody().getItems() == null
                || response.getResponse().getBody().getItems().getItem() == null
                || response.getResponse().getBody().getItems().getItem().isEmpty()) {
            return ComplexMetadataResolution.unavailable("BLD", "building metadata candidate unavailable");
        }
        List<ApisBldRecapResponse.Item> apartmentItems = response.getResponse().getBody().getItems().getItem().stream()
                .filter(Objects::nonNull)
                .filter(item -> "02000".equals(item.getMainPurpsCd()))
                .toList();
        if (apartmentItems.size() > 1) {
            return ComplexMetadataResolution.ambiguous("BLD", "building apartment candidate ambiguous pnu=" + pnu);
        }
        if (apartmentItems.isEmpty()) {
            return ComplexMetadataResolution.unavailable("BLD", "building apartment candidate unavailable");
        }
        ApisBldRecapResponse.Item item = apartmentItems.getFirst();
        return ComplexMetadataResolution.classify(
                "BLD",
                new ComplexMetadata(
                        null,
                        item.getHhldCnt(),
                        decimal(item.getPlatArea()),
                        decimal(item.getArchArea()),
                        decimal(item.getTotArea()),
                        decimal(item.getBcRat()),
                        decimal(item.getVlRat()),
                        null));
    }

    private ApisBldRecapResponse getBody(String path, String query) {
        if (baseUrl != null) {
            return restClient
                    .get()
                    .uri(ExternalApiUri.create(baseUrl, path, query))
                    .retrieve()
                    .body(ApisBldRecapResponse.class);
        }
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return restClient.get().uri(normalizedPath + "?" + query).retrieve().body(ApisBldRecapResponse.class);
    }

    private String buildingQuery(String pnu) {
        return "_type=" + ExternalApiUri.queryValue("json")
                + "&serviceKey=" + ExternalApiUri.serviceKeyQueryValue(serviceKey)
                + "&sigunguCd=" + ExternalApiUri.queryValue(pnu.substring(0, 5))
                + "&bjdongCd=" + ExternalApiUri.queryValue(pnu.substring(5, 10))
                + "&bun=" + ExternalApiUri.queryValue(pnu.substring(11, 15))
                + "&ji=" + ExternalApiUri.queryValue(pnu.substring(15, 19));
    }

    private ComplexMetadataLookupEvidence evidence(String requestedPnu, String resolvedPnu) {
        return new ComplexMetadataLookupEvidence(
                ComplexMetadataLookupPath.BUILDING_PNU, requestedPnu, resolvedPnu, null, null);
    }

    private BigDecimal decimal(Double value) {
        String text = value == null ? null : trimToNull(value.toString());
        if (text == null) return null;
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String trimToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private String redactSensitive(String message) {
        return message == null ? null : message.replaceAll("(?i)(serviceKey=)[^&\\s]+", "$1[REDACTED]");
    }
}
