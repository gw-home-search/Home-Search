package com.home.application.ingest.buildingregister;

import java.time.LocalDate;

@FunctionalInterface
public interface BuildingRegisterDailyRequestUsage {
    int usedRequests(LocalDate runDate);
}
