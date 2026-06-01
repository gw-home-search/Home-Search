package com.home.application.ingest;

import java.util.Optional;

@FunctionalInterface
public interface ComplexIdentityResolver {

	Optional<String> resolvePnu(OpenApiTradeItem item);

	static ComplexIdentityResolver noop() {
		return item -> Optional.empty();
	}
}
