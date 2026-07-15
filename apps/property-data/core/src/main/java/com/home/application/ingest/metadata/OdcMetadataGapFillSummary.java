package com.home.application.ingest.metadata;

public record OdcMetadataGapFillSummary(int targets, int requests, int applied, int ambiguous, int failed) {}
