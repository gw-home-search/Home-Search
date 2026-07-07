package com.home.application.ingest.matching;

import com.home.ingestcore.rtms.OpenApiTradeItem;

@FunctionalInterface
public interface ComplexMatcher {

	ComplexMatchResult match(OpenApiTradeItem item);
}
