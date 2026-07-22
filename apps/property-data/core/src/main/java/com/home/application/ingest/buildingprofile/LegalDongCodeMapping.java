package com.home.application.ingest.buildingprofile;

import java.time.LocalDate;

public record LegalDongCodeMapping(String oldLegalDongCode, String newLegalDongCode, LocalDate effectiveDate) {
    public LegalDongCodeMapping {
        if (oldLegalDongCode == null || !oldLegalDongCode.matches("[0-9]{10}")) {
            throw new IllegalArgumentException("oldLegalDongCode must be 10 digits");
        }
        if (newLegalDongCode == null || !newLegalDongCode.matches("[0-9]{10}")) {
            throw new IllegalArgumentException("newLegalDongCode must be 10 digits");
        }
        if (oldLegalDongCode.equals(newLegalDongCode))
            throw new IllegalArgumentException("legal dong code must change");
        if (effectiveDate == null) throw new IllegalArgumentException("effectiveDate is required");
    }
}
