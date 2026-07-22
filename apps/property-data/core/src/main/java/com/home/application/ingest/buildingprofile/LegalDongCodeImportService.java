package com.home.application.ingest.buildingprofile;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class LegalDongCodeImportService {
    private final LegalDongCodeImportRepository repository;

    public LegalDongCodeImportService(LegalDongCodeImportRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public int importMappings(LegalDongCodeImportCommand command) {
        Set<String> oldCodes = new HashSet<>();
        for (LegalDongCodeMapping mapping : command.mappings()) {
            if (!mapping.effectiveDate().equals(command.effectiveDate())) {
                throw new IllegalArgumentException("mapping effectiveDate does not match import");
            }
            if (!oldCodes.add(mapping.oldLegalDongCode())) {
                throw new IllegalArgumentException("duplicate oldLegalDongCode mapping");
            }
        }
        return repository.importMappings(command);
    }
}
