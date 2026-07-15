package com.home.domain.complex.buildingmetadata;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BuildingMetadataValues(
        Integer dongCnt,
        Integer unitCnt,
        BigDecimal platArea,
        BigDecimal archArea,
        BigDecimal totArea,
        BigDecimal bcRat,
        BigDecimal vlRat,
        LocalDate useDate) {
    public static BuildingMetadataValues empty() {
        return new BuildingMetadataValues(null, null, null, null, null, null, null, null);
    }

    public BuildingMetadataValues sanitized() {
        return new BuildingMetadataValues(
                positive(dongCnt),
                positive(unitCnt),
                positive(platArea),
                positive(archArea),
                positive(totArea),
                positive(bcRat),
                positive(vlRat),
                useDate);
    }

    public boolean hasAnyValue() {
        BuildingMetadataValues value = sanitized();
        return value.dongCnt != null
                || value.unitCnt != null
                || value.platArea != null
                || value.archArea != null
                || value.totArea != null
                || value.bcRat != null
                || value.vlRat != null
                || value.useDate != null;
    }

    public boolean hasAllAreaValues() {
        BuildingMetadataValues value = sanitized();
        return value.platArea != null
                && value.archArea != null
                && value.totArea != null
                && value.bcRat != null
                && value.vlRat != null;
    }

    private static Integer positive(Integer value) {
        return value != null && value > 0 ? value : null;
    }

    private static BigDecimal positive(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }
}
