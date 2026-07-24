package com.home.application.ingest.buildingregister;

@FunctionalInterface
public interface BuildingRegisterRawPageReceiver {
    long receive(BuildingRegisterRawPageReceiptCommand command);
}
