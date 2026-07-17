package com.home.chatbff.ai;

import com.home.chatbff.auth.VerifiedChatUser;
import com.home.chatbff.web.ChatbotQueryRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Component
final class WebClientChatbotAiClient implements ChatbotAiClient {
    private final WebClient client;

    WebClientChatbotAiClient(WebClient.Builder builder, ChatbotAiProperties properties) {
        this.client = builder.baseUrl(properties.baseUrl().toString()).build();
    }

    @Override
    public Mono<JsonNode> query(
            ChatbotQueryRequest request, String authorization, String requestId, VerifiedChatUser authenticatedUser) {
        if (authenticatedUser == null) return Mono.error(new ChatbotProviderUnavailableException());
        return client.post()
                .uri("/api/v1/chatbot/query")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .header("X-Request-Id", requestId)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, ignored -> Mono.error(new ChatbotProviderUnavailableException()))
                .bodyToMono(JsonNode.class)
                .filter(JsonNode::isObject)
                .switchIfEmpty(Mono.error(new ChatbotProviderUnavailableException()))
                .onErrorMap(WebClientException.class, ignored -> new ChatbotProviderUnavailableException());
    }
}
