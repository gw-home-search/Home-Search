package com.home.application.ingest;

@FunctionalInterface
public interface ComplexMatcher {

	ComplexMatchResult match(OpenApiTradeItem item);
}
