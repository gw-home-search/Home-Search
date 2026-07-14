package com.home.application.ingest.buildingmetadata;

import com.home.domain.complex.metadata.ComplexMetadataFailureKind;

public class BuildingMetadataProviderException extends RuntimeException {
    private final ComplexMetadataFailureKind failureKind;
    private final boolean fatal;

    public BuildingMetadataProviderException(String message, ComplexMetadataFailureKind failureKind, boolean fatal) {
        super(message);
        this.failureKind = failureKind;
        this.fatal = fatal;
    }

    public ComplexMetadataFailureKind failureKind() {
        return failureKind;
    }

    public boolean fatal() {
        return fatal;
    }
}
