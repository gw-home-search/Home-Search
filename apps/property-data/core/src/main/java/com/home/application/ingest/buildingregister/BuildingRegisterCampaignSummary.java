package com.home.application.ingest.buildingregister;

public record BuildingRegisterCampaignSummary(
        int targetCount, int pnuCount, int requestCount, int matchCount, boolean completed) {}
