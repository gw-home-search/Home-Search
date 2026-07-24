package com.home.application.ingest.buildingregister;

public class BuildingRegisterRequestBudgetExceededException extends IllegalStateException {
    private final int consumedRequests;

    public BuildingRegisterRequestBudgetExceededException(int consumedRequests) {
        super("building register request budget exhausted: " + consumedRequests);
        this.consumedRequests = consumedRequests;
    }

    public int consumedRequests() {
        return consumedRequests;
    }
}
