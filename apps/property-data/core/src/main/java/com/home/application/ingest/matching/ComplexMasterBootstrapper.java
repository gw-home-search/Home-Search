package com.home.application.ingest.matching;

import com.home.ingestcore.rtms.OpenApiTradeItem;

@FunctionalInterface
public interface ComplexMasterBootstrapper {

    ComplexMasterBootstrapResult bootstrap(OpenApiTradeItem item);

    static ComplexMasterBootstrapper noop() {
        return item -> ComplexMasterBootstrapResult.noop();
    }
}
