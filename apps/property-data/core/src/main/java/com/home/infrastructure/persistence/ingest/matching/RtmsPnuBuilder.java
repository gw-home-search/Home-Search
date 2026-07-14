package com.home.infrastructure.persistence.ingest.matching;

import com.home.application.ingest.normalization.RtmsJibunPnuNormalizer;
import com.home.ingestcore.rtms.OpenApiTradeItem;
import java.util.Optional;

final class RtmsPnuBuilder {

    private RtmsPnuBuilder() {}

    static Optional<String> build(OpenApiTradeItem item) {
        return Optional.ofNullable(RtmsJibunPnuNormalizer.normalize(item).derivedPnu());
    }
}
