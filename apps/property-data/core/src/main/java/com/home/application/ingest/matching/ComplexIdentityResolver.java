package com.home.application.ingest.matching;

import com.home.ingestcore.rtms.OpenApiTradeItem;
import java.util.Optional;

@FunctionalInterface
public interface ComplexIdentityResolver {

    Optional<String> resolvePnu(OpenApiTradeItem item);

    static ComplexIdentityResolver noop() {
        return item -> Optional.empty();
    }
}
