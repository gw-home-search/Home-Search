package com.home.infrastructure.observability;

import java.util.Objects;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

class MapEndpointMetricsInterceptor implements HandlerInterceptor {

	static final String METRIC_NAME = "home.search.map.requests";

	private final MeterRegistry meterRegistry;

	MapEndpointMetricsInterceptor(MeterRegistry meterRegistry) {
		this.meterRegistry = Objects.requireNonNull(meterRegistry);
	}

	@Override
	public void afterCompletion(
		HttpServletRequest request,
		HttpServletResponse response,
		Object handler,
		Exception ex
	) {
		String endpoint = endpoint(request);
		if (endpoint == null) {
			return;
		}

		int status = response.getStatus();
		String outcome = ex != null || status >= 400 ? "error" : "success";
		Counter.builder(METRIC_NAME)
			.description("map endpoint request counts")
			.tag("endpoint", endpoint)
			.tag("outcome", outcome)
			.register(meterRegistry)
			.increment();
	}

	private String endpoint(HttpServletRequest request) {
		String path = request.getRequestURI();
		String contextPath = request.getContextPath();
		if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
			path = path.substring(contextPath.length());
		}
		return switch (path) {
			case "/api/v1/map/complexes" -> "complexes";
			case "/api/v1/map/regions" -> "regions";
			default -> null;
		};
	}
}
