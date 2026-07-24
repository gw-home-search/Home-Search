package com.home.application.ingest.buildingregister;

public final class BuildingRegisterPageFetchException extends RuntimeException {
    private final String failureCode;

    public BuildingRegisterPageFetchException(String failureCode) {
        super(requireFailureCode(failureCode));
        this.failureCode = failureCode;
    }

    public String failureCode() {
        return failureCode;
    }

    private static String requireFailureCode(String failureCode) {
        if (!"TRANSPORT_TIMEOUT".equals(failureCode) && !"TRANSPORT_IO".equals(failureCode)) {
            throw new IllegalArgumentException("unsupported building register fetch failure code");
        }
        return failureCode;
    }
}
