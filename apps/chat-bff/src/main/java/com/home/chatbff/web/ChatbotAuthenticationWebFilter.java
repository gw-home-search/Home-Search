package com.home.chatbff.web;

import com.home.chatbff.auth.AuthenticationRequiredException;
import com.home.chatbff.auth.ChatUserAuthenticator;
import com.home.chatbff.auth.VerifiedChatUser;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
final class ChatbotAuthenticationWebFilter implements WebFilter {
    static final String USER_ATTRIBUTE = ChatbotAuthenticationWebFilter.class.getName() + ".user";
    private final ChatUserAuthenticator authenticator;
    private final ObjectMapper objectMapper;

    ChatbotAuthenticationWebFilter(ChatUserAuthenticator authenticator, ObjectMapper objectMapper) {
        this.authenticator = authenticator;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith("/api/v1/chatbot/")) {
            return chain.filter(exchange);
        }
        try {
            String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            VerifiedChatUser user = authorization == null ? null : authenticator.authenticate(authorization);
            if (user == null) throw new AuthenticationRequiredException();
            exchange.getAttributes().put(USER_ATTRIBUTE, user);
            return chain.filter(exchange);
        } catch (AuthenticationRequiredException exception) {
            return writeUnauthorized(exchange);
        }
    }

    private Mono<Void> writeUnauthorized(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        byte[] body = objectMapper.writeValueAsBytes(ChatbotProblems.authenticationRequired(
                exchange.getRequest().getPath().value(), RequestIdWebFilter.required(exchange)));
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
