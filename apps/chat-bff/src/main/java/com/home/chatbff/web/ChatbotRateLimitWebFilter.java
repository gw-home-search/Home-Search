package com.home.chatbff.web;

import com.home.chatbff.auth.VerifiedChatUser;
import com.home.chatbff.ratelimit.ChatbotRateLimitUnavailableException;
import com.home.chatbff.ratelimit.ChatbotRateLimitedException;
import com.home.chatbff.ratelimit.ChatbotRateLimiter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
final class ChatbotRateLimitWebFilter implements WebFilter {
    private final ChatbotRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    ChatbotRateLimitWebFilter(ChatbotRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith("/api/v1/chatbot/")) {
            return chain.filter(exchange);
        }
        VerifiedChatUser user = exchange.getAttribute(ChatbotAuthenticationWebFilter.USER_ATTRIBUTE);
        if (user == null) return writeUnavailable(exchange);
        return rateLimiter
                .acquire(user.userId())
                .then(chain.filter(exchange))
                .onErrorResume(ChatbotRateLimitedException.class, exception -> writeLimited(exchange, exception))
                .onErrorResume(ChatbotRateLimitUnavailableException.class, ignored -> writeUnavailable(exchange));
    }

    private Mono<Void> writeLimited(ServerWebExchange exchange, ChatbotRateLimitedException exception) {
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()));
        return writeProblem(
                exchange,
                HttpStatus.TOO_MANY_REQUESTS,
                ChatbotProblems.rateLimited(
                        exchange.getRequest().getPath().value(), RequestIdWebFilter.required(exchange)));
    }

    private Mono<Void> writeUnavailable(ServerWebExchange exchange) {
        return writeProblem(
                exchange,
                HttpStatus.SERVICE_UNAVAILABLE,
                ChatbotProblems.rateLimitUnavailable(
                        exchange.getRequest().getPath().value(), RequestIdWebFilter.required(exchange)));
    }

    private Mono<Void> writeProblem(ServerWebExchange exchange, HttpStatus status, Object body) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().setCacheControl(CacheControl.noStore());
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }
}
