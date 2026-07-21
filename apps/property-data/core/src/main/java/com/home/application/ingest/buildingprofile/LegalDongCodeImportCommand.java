package com.home.application.ingest.buildingprofile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LegalDongCodeImportCommand(
        UUID importId,
        LocalDate effectiveDate,
        String sourceSha256,
        String sourceName,
        List<LegalDongCodeMapping> mappings) {
    public LegalDongCodeImportCommand {
        if (importId == null || effectiveDate == null)
            throw new IllegalArgumentException("import identity is required");
        if (sourceSha256 == null || !sourceSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sourceSha256 must be lowercase SHA-256");
        }
        if (sourceName == null || sourceName.isBlank() || sourceName.length() > 300) {
            throw new IllegalArgumentException("sourceName must be 1..300 characters");
        }
        mappings = List.copyOf(mappings);
        if (mappings.isEmpty()) throw new IllegalArgumentException("mapping file must not be empty");
    }
}
