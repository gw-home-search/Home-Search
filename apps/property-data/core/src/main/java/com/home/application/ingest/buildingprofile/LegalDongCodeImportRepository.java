package com.home.application.ingest.buildingprofile;

public interface LegalDongCodeImportRepository {
    int importMappings(LegalDongCodeImportCommand command);
}
