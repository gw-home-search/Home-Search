package com.home.chatbff.ai;

import com.home.chatbff.auth.VerifiedChatUser;
import com.home.chatbff.web.ChatbotQueryRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

public interface ChatbotAiClient {
    Mono<JsonNode> query(
            ChatbotQueryRequest request, String authorization, String requestId, VerifiedChatUser authenticatedUser);

    default Flux<ChatbotAiStreamEvent> stream(
            ChatbotQueryRequest request, String authorization, String requestId, VerifiedChatUser authenticatedUser) {
        return query(request, authorization, requestId, authenticatedUser)
                .map(response -> new ChatbotAiStreamEvent("final", response))
                .flux();
    }
}
