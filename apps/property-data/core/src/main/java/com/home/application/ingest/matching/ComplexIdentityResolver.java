package com.home.application.ingest.matching;

import java.util.Optional;
import com.home.ingestcore.rtms.OpenApiTradeItem;

@FunctionalInterface
public interface ComplexIdentityResolver {

	Optional<String> resolvePnu(OpenApiTradeItem item);

	static ComplexIdentityResolver noop() {
		return item -> Optional.empty();
	}
}
