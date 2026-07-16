package com.home.infrastructure.external.complex;

import com.home.application.ingest.metadata.ComplexMetadata;
import com.home.application.ingest.metadata.ComplexMetadataLookup;
import com.home.application.ingest.metadata.ComplexMetadataLookupEvidence;
import com.home.application.ingest.metadata.ComplexMetadataResolution;
import com.home.application.ingest.metadata.OdcloudPnuPrefixAliasLookup;
import com.home.domain.complex.metadata.ComplexMetadataFailureKind;
import com.home.domain.complex.metadata.ComplexMetadataLookupPath;
import com.home.infrastructure.external.ExternalApiUri;
import com.home.infrastructure.external.odcloud.dto.OdcloudAptResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

final class OdcComplexMetadataAdapter {

    private static final Logger log = LoggerFactory.getLogger(OdcComplexMetadataAdapter.class);

    private final RestClient restClient;
    private final String baseUrl;
    private final String serviceKey;
    private final String aptPath;
    private final OdcloudPnuPrefixAliasLookup aliasLookup;

    OdcComplexMetadataAdapter(
            RestClient restClient,
            String baseUrl,
            String serviceKey,
            String aptPath,
            OdcloudPnuPrefixAliasLookup aliasLookup) {
        this.restClient = Objects.requireNonNull(restClient);
        this.baseUrl = trimToNull(baseUrl);
        this.serviceKey = trimToNull(serviceKey);
        this.aptPath = Objects.requireNonNull(aptPath);
        this.aliasLookup = Objects.requireNonNull(aliasLookup);
    }

    boolean isConfigured() {
        return serviceKey != null;
    }

    ComplexMetadataResolution resolve(ComplexMetadataLookup lookup) {
        String pnu = lookup.pnu();
        if (!isConfigured() || trimToNull(pnu) == null) {
            return ComplexMetadataResolution.unavailable(
                    "ODC", ComplexMetadataFailureKind.INPUT_INSUFFICIENT, "ODC lookup skipped");
        }
        try {
            List<OdcloudAptResponse.Item> matches = exactMatches(pnu);
            if (matches.size() > 1) {
                OdcloudAptResponse.Item nameMatched = chooseByName(lookup.aptName(), matches);
                if (nameMatched != null) {
                    return metadataResolution(nameMatched)
                            .withLookupEvidence(evidence(
                                    ComplexMetadataLookupPath.CANONICAL_PNU_NAME, pnu, pnu, null, matches.size()));
                }
                return ComplexMetadataResolution.ambiguous("ODC", "ODC PNU candidate ambiguous pnu=" + pnu)
                        .withLookupEvidence(
                                evidence(ComplexMetadataLookupPath.CANONICAL_PNU, pnu, null, null, matches.size()));
            }
            if (matches.isEmpty()) {
                return resolveApprovedAlias(lookup);
            }
            return metadataResolution(matches.getFirst())
                    .withLookupEvidence(evidence(ComplexMetadataLookupPath.CANONICAL_PNU, pnu, pnu, null, 1));
        } catch (RestClientException exception) {
            log.warn(
                    "ODC complex metadata lookup failed pnu={} errorType={}",
                    pnu,
                    exception.getClass().getSimpleName());
            return ComplexMetadataResolution.failed(
                            "ODC", ComplexMetadataFailureKind.TRANSIENT, redactSensitive(exception.getMessage()))
                    .withLookupEvidence(evidence(ComplexMetadataLookupPath.CANONICAL_PNU, pnu, null, null, null));
        }
    }

    private ComplexMetadataResolution resolveApprovedAlias(ComplexMetadataLookup lookup) {
        return aliasLookup
                .findApprovedByCanonicalPnu(lookup.pnu())
                .map(alias -> {
                    String sourcePnu = alias.translate(lookup.pnu());
                    List<OdcloudAptResponse.Item> matches = exactMatches(sourcePnu);
                    ComplexMetadataLookupEvidence lookupEvidence = evidence(
                            ComplexMetadataLookupPath.APPROVED_PREFIX_ALIAS,
                            lookup.pnu(),
                            sourcePnu,
                            alias.id(),
                            matches.size());
                    if (matches.isEmpty()) {
                        return ComplexMetadataResolution.unavailable("ODC", "ODC approved alias candidate unavailable")
                                .withLookupEvidence(lookupEvidence);
                    }
                    if (matches.size() > 1) {
                        OdcloudAptResponse.Item nameMatched = chooseByName(lookup.aptName(), matches);
                        if (nameMatched == null) {
                            return ComplexMetadataResolution.ambiguous("ODC", "ODC approved alias candidate ambiguous")
                                    .withLookupEvidence(lookupEvidence);
                        }
                        return metadataResolution(nameMatched)
                                .withLookupEvidence(evidence(
                                        ComplexMetadataLookupPath.APPROVED_PREFIX_ALIAS_NAME,
                                        lookup.pnu(),
                                        sourcePnu,
                                        alias.id(),
                                        matches.size()));
                    }
                    return metadataResolution(matches.getFirst()).withLookupEvidence(lookupEvidence);
                })
                .orElseGet(() -> ComplexMetadataResolution.unavailable(
                                "ODC", "ODC exact PNU candidate unavailable pnu=" + lookup.pnu())
                        .withLookupEvidence(
                                evidence(ComplexMetadataLookupPath.CANONICAL_PNU, lookup.pnu(), null, null, 0)));
    }

    private List<OdcloudAptResponse.Item> exactMatches(String pnu) {
        OdcloudAptResponse response = getBody(pnu);
        return response == null || response.getData() == null
                ? List.of()
                : response.getData().stream()
                        .filter(Objects::nonNull)
                        .filter(item -> pnu.equals(trimToNull(item.getPnu())))
                        .toList();
    }

    private OdcloudAptResponse getBody(String pnu) {
        String query = "page=" + ExternalApiUri.queryValue(1)
                + "&perPage=" + ExternalApiUri.queryValue(20)
                + "&cond%5BPNU::EQ%5D=" + ExternalApiUri.queryValue(pnu)
                + "&serviceKey=" + ExternalApiUri.serviceKeyQueryValue(serviceKey);
        if (baseUrl != null) {
            return restClient
                    .get()
                    .uri(ExternalApiUri.create(baseUrl, aptPath, query))
                    .retrieve()
                    .body(OdcloudAptResponse.class);
        }
        String normalizedPath = aptPath.startsWith("/") ? aptPath : "/" + aptPath;
        return restClient.get().uri(normalizedPath + "?" + query).retrieve().body(OdcloudAptResponse.class);
    }

    private ComplexMetadataResolution metadataResolution(OdcloudAptResponse.Item selected) {
        return ComplexMetadataResolution.classify(
                "ODC",
                new ComplexMetadata(
                        selected.getDongCnt(),
                        selected.getUnitCnt(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        parseUseDate(selected.getUseaprDt())));
    }

    private ComplexMetadataLookupEvidence evidence(
            ComplexMetadataLookupPath path,
            String requestedPnu,
            String resolvedSourcePnu,
            Long aliasId,
            Integer candidateCount) {
        return new ComplexMetadataLookupEvidence(path, requestedPnu, resolvedSourcePnu, aliasId, candidateCount);
    }

    private OdcloudAptResponse.Item chooseByName(String aptName, List<OdcloudAptResponse.Item> candidates) {
        String target = normalizeName(aptName);
        if (target.isEmpty()) return null;
        int bestScore = 0;
        OdcloudAptResponse.Item best = null;
        boolean tie = false;
        for (OdcloudAptResponse.Item candidate : candidates) {
            int score = scoreName(target, candidate);
            if (score == 0) continue;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
                tie = false;
            } else if (score == bestScore) {
                tie = true;
            }
        }
        return bestScore > 0 && !tie ? best : null;
    }

    private int scoreName(String target, OdcloudAptResponse.Item candidate) {
        for (String name :
                new String[] {candidate.getComplexNm1(), candidate.getComplexNm2(), candidate.getComplexNm3()}) {
            String normalized = normalizeName(name);
            if (!normalized.isEmpty() && target.equals(normalized)) return 3;
        }
        return 0;
    }

    private String normalizeName(String value) {
        String text = trimToNull(value);
        return text == null
                ? ""
                : text.replaceAll("\\s+", "")
                        .replaceAll("[()\\[\\]{}.,·\\-_/]", "")
                        .toLowerCase(Locale.ROOT);
    }

    private LocalDate parseUseDate(String value) {
        String text = trimToNull(value);
        if (text == null) return null;
        String normalized = text.replace("-", "").replace("/", "").replace(".", "");
        try {
            return normalized.length() == 8
                    ? LocalDate.parse(normalized, DateTimeFormatter.BASIC_ISO_DATE)
                    : LocalDate.parse(text);
        } catch (DateTimeParseException exception) {
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
