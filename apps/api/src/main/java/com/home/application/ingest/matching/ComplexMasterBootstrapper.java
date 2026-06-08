package com.home.application.ingest.matching;

import com.home.application.ingest.trade.OpenApiTradeItem;

@FunctionalInterface
public interface ComplexMasterBootstrapper {

	ComplexMasterBootstrapResult bootstrap(OpenApiTradeItem item);

	static ComplexMasterBootstrapper noop() {
		return item -> ComplexMasterBootstrapResult.noop();
	}
}
