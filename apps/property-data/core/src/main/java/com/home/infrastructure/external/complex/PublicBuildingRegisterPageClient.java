package com.home.infrastructure.external.complex;

import com.home.application.ingest.buildingregister.BuildingRegisterPageClient;
import com.home.application.ingest.buildingregister.BuildingRegisterPageRequest;
import com.home.application.ingest.buildingregister.BuildingRegisterPageResponse;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import com.home.infrastructure.external.ExternalApiUri;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.client.RestClient;

public class PublicBuildingRegisterPageClient implements BuildingRegisterPageClient {
    private static final long MAX_RESPONSE_BYTES = 2_097_152;

    private final RestClient client;
    private final String baseUrl;
    private final String serviceKey;
    private final Map<BuildingRegisterEndpoint, String> paths;
    private final long minIntervalMillis;
    private long lastRequestAt;

    public PublicBuildingRegisterPageClient(
            RestClient client,
            String baseUrl,
            String serviceKey,
            String recapPath,
            String titlePath,
            String basicOverviewPath,
            long minIntervalMillis) {
        this.client = Objects.requireNonNull(client);
        this.baseUrl = Objects.requireNonNull(baseUrl);
        this.serviceKey = trim(serviceKey);
        this.paths = Map.of(
                BuildingRegisterEndpoint.RECAP_TITLE,
                required(recapPath, "recapPath"),
                BuildingRegisterEndpoint.TITLE,
                required(titlePath, "titlePath"),
                BuildingRegisterEndpoint.BASIC_OVERVIEW,
                required(basicOverviewPath, "basicOverviewPath"));
        this.minIntervalMillis = Math.max(0, minIntervalMillis);
    }

    @Override
    public BuildingRegisterPageResponse fetch(BuildingRegisterPageRequest request) {
        if (serviceKey == null) throw new IllegalStateException("BLD_SERVICE_KEY is required");
        throttle();
        String path = paths.get(request.endpoint());
        return client.get()
                .uri(ExternalApiUri.create(baseUrl, path, query(request)))
                .exchange((httpRequest, response) -> {
                    try {
                        BodyRead body = readBody(response.getBody());
                        return new BuildingRegisterPageResponse(
                                request.endpoint(),
                                request.pnu(),
                                request.pageNo(),
                                request.pageSize(),
                                response.getStatusCode().value(),
                                body.body(),
                                body.byteSize(),
                                body.hash(),
                                body.oversized());
                    } catch (IOException exception) {
                        throw new IllegalStateException("building register response read failed", exception);
                    }
                });
    }

    private String query(BuildingRegisterPageRequest request) {
        String pnu = request.pnu();
        return "_type=" + ExternalApiUri.queryValue("json")
                + "&pageNo=" + ExternalApiUri.queryValue(request.pageNo())
                + "&numOfRows=" + ExternalApiUri.queryValue(request.pageSize())
                + "&sigunguCd=" + ExternalApiUri.queryValue(pnu.substring(0, 5))
                + "&bjdongCd=" + ExternalApiUri.queryValue(pnu.substring(5, 10))
                + "&platGbCd=" + ExternalApiUri.queryValue(platGbCd(pnu))
                + "&bun=" + ExternalApiUri.queryValue(pnu.substring(11, 15))
                + "&ji=" + ExternalApiUri.queryValue(pnu.substring(15, 19))
                + "&serviceKey=" + ExternalApiUri.serviceKeyQueryValue(serviceKey);
    }

    private String platGbCd(String pnu) {
        return switch (pnu.charAt(10)) {
            case '1' -> "0";
            case '2' -> "1";
            default -> throw new IllegalArgumentException("PNU land category must be 1 or 2");
        };
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

    private synchronized void throttle() {
        long remaining = minIntervalMillis - (System.currentTimeMillis() - lastRequestAt);
        if (remaining > 0) {
            try {
                Thread.sleep(remaining);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("building register request interrupted", exception);
            }
        }
        lastRequestAt = System.currentTimeMillis();
    }

    private String required(String value, String name) {
        String result = trim(value);
        if (result == null) throw new IllegalArgumentException(name + " is required");
        return result;
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record BodyRead(String body, long byteSize, String hash, boolean oversized) {}
}
