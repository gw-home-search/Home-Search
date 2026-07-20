package com.home.application.ingest.buildingregister;

public class BuildingRegisterRequestBudgetExceededException extends IllegalStateException {
    public BuildingRegisterRequestBudgetExceededException(int maxRequests) {
        super("building register request budget exhausted: " + maxRequests);
    }
}
