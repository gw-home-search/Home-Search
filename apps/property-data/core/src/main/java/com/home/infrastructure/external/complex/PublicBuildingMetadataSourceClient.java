package com.home.infrastructure.external.complex;

import com.home.application.ingest.buildingmetadata.BuildingMetadataSourceClient;
import com.home.application.ingest.buildingmetadata.BuildingMetadataSourceResponse;
import com.home.domain.complex.buildingmetadata.BuildingMetadataSourceKind;
import com.home.infrastructure.external.ExternalApiUri;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.web.client.RestClient;

public class PublicBuildingMetadataSourceClient implements BuildingMetadataSourceClient {
    private static final long MAX_RESPONSE_BYTES = 2_097_152;
    private final RestClient odcClient;
    private final String odcBaseUrl;
    private final String odcServiceKey;
    private final String odcPath;
    private final RestClient buildingClient;
    private final String buildingBaseUrl;
    private final String buildingServiceKey;
    private final String recapPath;
    private final String titlePath;
    private final long minIntervalMillis;
    private long lastRequestAt;

    public PublicBuildingMetadataSourceClient(
            RestClient odcClient,
            String odcBaseUrl,
            String odcServiceKey,
            String odcPath,
            RestClient buildingClient,
            String buildingBaseUrl,
            String buildingServiceKey,
            String recapPath,
            String titlePath,
            long minIntervalMillis) {
        this.odcClient = Objects.requireNonNull(odcClient);
        this.odcBaseUrl = Objects.requireNonNull(odcBaseUrl);
        this.odcServiceKey = trim(odcServiceKey);
        this.odcPath = Objects.requireNonNull(odcPath);
        this.buildingClient = Objects.requireNonNull(buildingClient);
        this.buildingBaseUrl = Objects.requireNonNull(buildingBaseUrl);
        this.buildingServiceKey = trim(buildingServiceKey);
        this.recapPath = Objects.requireNonNull(recapPath);
        this.titlePath = Objects.requireNonNull(titlePath);
        this.minIntervalMillis = Math.max(0, minIntervalMillis);
    }

    @Override
    public boolean isConfigured() {
        return buildingServiceKey != null;
    }

    @Override
    public BuildingMetadataSourceResponse fetch(BuildingMetadataSourceKind sourceKind, String pnu) {
        if (pnu == null || !pnu.matches("\\d{19}")) throw new IllegalArgumentException("PNU must be 19 digits");
        if (sourceKind == BuildingMetadataSourceKind.ODC_APT && odcServiceKey == null)
            throw new IllegalStateException("ODC_SERVICE_KEY is required");
        if (sourceKind != BuildingMetadataSourceKind.ODC_APT && buildingServiceKey == null)
            throw new IllegalStateException("BLD_SERVICE_KEY is required");
        throttle();
        return switch (sourceKind) {
            case ODC_APT -> exchange(odcClient, odcBaseUrl, odcPath, odcQuery(pnu), sourceKind, pnu);
            case BLD_RECAP_TITLE ->
                exchange(buildingClient, buildingBaseUrl, recapPath, buildingQuery(pnu), sourceKind, pnu);
            case BLD_TITLE -> exchange(buildingClient, buildingBaseUrl, titlePath, buildingQuery(pnu), sourceKind, pnu);
        };
    }

    private BuildingMetadataSourceResponse exchange(
            RestClient client, String baseUrl, String path, String query, BuildingMetadataSourceKind kind, String pnu) {
        return client.get().uri(ExternalApiUri.create(baseUrl, path, query)).exchange((request, response) -> {
            try {
                BodyRead body = readBody(response.getBody());
                return new BuildingMetadataSourceResponse(
                        kind,
                        pnu,
                        response.getStatusCode().value(),
                        null,
                        body.body(),
                        body.byteSize(),
                        body.hash(),
                        body.oversized());
            } catch (IOException exception) {
                throw new IllegalStateException("external source body read failed", exception);
            }
        });
    }

    private BodyRead readBody(java.io.InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            long byteSize = 0;
            boolean oversized = false;
            byte[] chunk = new byte[8192];
            for (int read = input.read(chunk); read >= 0; read = input.read(chunk)) {
                if (read == 0) continue;
                digest.update(chunk, 0, read);
                byteSize += read;
                if (!oversized && byteSize <= MAX_RESPONSE_BYTES) {
                    buffer.write(chunk, 0, read);
                } else if (!oversized) {
                    oversized = true;
                    buffer = null;
                }
            }
            String body = oversized ? null : buffer.toString(StandardCharsets.UTF_8);
            return new BodyRead(body, byteSize, HexFormat.of().formatHex(digest.digest()), oversized);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String odcQuery(String pnu) {
        return "page=" + ExternalApiUri.queryValue(1) + "&perPage=" + ExternalApiUri.queryValue(100)
                + "&cond%5BPNU::EQ%5D=" + ExternalApiUri.queryValue(pnu)
                + "&serviceKey=" + ExternalApiUri.serviceKeyQueryValue(odcServiceKey);
    }

    private String buildingQuery(String pnu) {
        return "_type=" + ExternalApiUri.queryValue("json")
                + "&pageNo=" + ExternalApiUri.queryValue(1) + "&numOfRows=" + ExternalApiUri.queryValue(100)
                + "&sigunguCd=" + ExternalApiUri.queryValue(pnu.substring(0, 5))
                + "&bjdongCd=" + ExternalApiUri.queryValue(pnu.substring(5, 10))
                + "&platGbCd=" + ExternalApiUri.queryValue(buildingPlatGbCd(pnu))
                + "&bun=" + ExternalApiUri.queryValue(pnu.substring(11, 15))
                + "&ji=" + ExternalApiUri.queryValue(pnu.substring(15, 19))
                + "&serviceKey=" + ExternalApiUri.serviceKeyQueryValue(buildingServiceKey);
    }

    private String buildingPlatGbCd(String pnu) {
        return switch (pnu.charAt(10)) {
            case '1' -> "0";
            case '2' -> "1";
            default -> throw new IllegalArgumentException("PNU land category must be 1 or 2");
        };
    }

    private synchronized void throttle() {
        long now = System.currentTimeMillis();
        long remaining = minIntervalMillis - (now - lastRequestAt);
        if (remaining > 0) {
            try {
                Thread.sleep(remaining);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("metadata request interrupted", exception);
            }
        }
        lastRequestAt = System.currentTimeMillis();
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record BodyRead(String body, long byteSize, String hash, boolean oversized) {}
}
