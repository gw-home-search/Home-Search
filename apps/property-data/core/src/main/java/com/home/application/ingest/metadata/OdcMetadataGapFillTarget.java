package com.home.application.ingest.metadata;

import java.util.Objects;

public record OdcMetadataGapFillTarget(ComplexMetadataLookup lookup, int pnuComplexCount) {
    public OdcMetadataGapFillTarget {
        Objects.requireNonNull(lookup, "lookup is required");
    }
}
