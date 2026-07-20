package com.home.application.ingest.buildingregister;

public final class BuildingRegisterFatalProviderException extends RuntimeException {
    public BuildingRegisterFatalProviderException(String providerCode) {
        super("building register authentication or quota failure: " + providerCode);
    }
}
