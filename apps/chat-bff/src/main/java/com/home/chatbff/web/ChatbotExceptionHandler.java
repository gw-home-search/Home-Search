package com.home.chatbff.web;

import com.home.chatbff.ai.ChatbotProviderUnavailableException;
import com.home.chatbff.ai.ChatbotTimeoutException;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice
final class ChatbotExceptionHandler {
    @ExceptionHandler(WebExchangeBindException.class)
    ProblemDetail invalidRequest(WebExchangeBindException ignored, ServerWebExchange exchange) {
        return ChatbotProblems.invalidRequest(path(exchange), RequestIdWebFilter.required(exchange));
    }

    @ExceptionHandler(ServerWebInputException.class)
    ProblemDetail invalidInput(ServerWebInputException ignored, ServerWebExchange exchange) {
        return ChatbotProblems.invalidRequest(path(exchange), RequestIdWebFilter.required(exchange));
    }

    @ExceptionHandler(DecodingException.class)
    ProblemDetail invalidJsonType(DecodingException ignored, ServerWebExchange exchange) {
        return ChatbotProblems.invalidRequest(path(exchange), RequestIdWebFilter.required(exchange));
    }

    @ExceptionHandler(ChatbotProviderUnavailableException.class)
    ProblemDetail providerUnavailable(ChatbotProviderUnavailableException ignored, ServerWebExchange exchange) {
        return ChatbotProblems.providerUnavailable(path(exchange), RequestIdWebFilter.required(exchange));
    }

    @ExceptionHandler(ChatbotTimeoutException.class)
    ProblemDetail timeout(ChatbotTimeoutException ignored, ServerWebExchange exchange) {
        return ChatbotProblems.timeout(path(exchange), RequestIdWebFilter.required(exchange));
    }

    private static String path(ServerWebExchange exchange) {
        return exchange.getRequest().getPath().value();
    }
}
