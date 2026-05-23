package com.home.application.ingest;

@FunctionalInterface
public interface ComplexMasterBootstrapper {

	ComplexMasterBootstrapResult bootstrap(OpenApiTradeItem item);

	static ComplexMasterBootstrapper noop() {
		return item -> ComplexMasterBootstrapResult.noop();
	}
}
