package com.home.application.ingest.buildingregister;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public record BuildingRegisterRawPageReceiptCommand(
        long endpointSnapshotId,
        UUID requestId,
        int pageNo,
        int attemptNo,
        String responseBody,
        String bodySha256,
        int byteCount,
        Integer httpStatus,
        String providerStatus) {
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    public BuildingRegisterRawPageReceiptCommand {
        if (endpointSnapshotId <= 0) throw new IllegalArgumentException("endpointSnapshotId must be positive");
        Objects.requireNonNull(requestId, "requestId");
        if (pageNo <= 0 || attemptNo <= 0) throw new IllegalArgumentException("pageNo and attemptNo must be positive");
        if (bodySha256 == null || !bodySha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("bodySha256 must be a lowercase SHA-256 hex value");
        }
        if (byteCount < 0) throw new IllegalArgumentException("byteCount must not be negative");
        if (responseBody != null) {
            int actualBytes = responseBody.getBytes(StandardCharsets.UTF_8).length;
            if (actualBytes > MAX_BODY_BYTES) throw new IllegalArgumentException("responseBody exceeds 2 MiB");
            if (actualBytes != byteCount) throw new IllegalArgumentException("byteCount does not match responseBody");
        }
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("httpStatus must be between 100 and 599");
        }
    }
}
