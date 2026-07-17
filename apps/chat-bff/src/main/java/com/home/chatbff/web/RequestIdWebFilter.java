package com.home.chatbff.web;

import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class RequestIdWebFilter implements WebFilter {
    static final String ATTRIBUTE = RequestIdWebFilter.class.getName() + ".requestId";
    static final String HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = requestId(exchange.getRequest().getHeaders().getFirst(HEADER));
        exchange.getAttributes().put(ATTRIBUTE, requestId);
        exchange.getResponse().getHeaders().set(HEADER, requestId);
        return chain.filter(exchange);
    }

    static String required(ServerWebExchange exchange) {
        Object value = exchange.getAttribute(ATTRIBUTE);
        return value instanceof String requestId ? requestId : UUID.randomUUID().toString();
    }

    private static String requestId(String candidate) {
        try {
            if (candidate != null && UUID.fromString(candidate).toString().equals(candidate.toLowerCase())) {
                return UUID.fromString(candidate).toString();
            }
        } catch (IllegalArgumentException ignored) {
            // Generate a request id below.
        }
        return UUID.randomUUID().toString();
    }
}
