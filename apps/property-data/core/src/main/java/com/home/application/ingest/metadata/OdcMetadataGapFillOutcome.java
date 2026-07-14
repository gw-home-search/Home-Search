package com.home.application.ingest.metadata;

import com.home.domain.complex.metadata.ComplexMetadataStatus;

public record OdcMetadataGapFillOutcome(ComplexMetadataStatus status, boolean projectionApplied) {
    public static OdcMetadataGapFillOutcome applied() {
        return new OdcMetadataGapFillOutcome(ComplexMetadataStatus.PARTIAL, true);
    }

    public static OdcMetadataGapFillOutcome ambiguous() {
        return new OdcMetadataGapFillOutcome(ComplexMetadataStatus.AMBIGUOUS, false);
    }
}
