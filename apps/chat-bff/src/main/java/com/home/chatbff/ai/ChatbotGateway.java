package com.home.chatbff.ai;

import com.home.chatbff.auth.VerifiedChatUser;
import com.home.chatbff.web.ChatbotQueryRequest;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Service
public class ChatbotGateway {
    private final ChatbotAiClient client;
    private final ChatbotAiProperties properties;

    public ChatbotGateway(ChatbotAiClient client, ChatbotAiProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public Mono<JsonNode> query(
            ChatbotQueryRequest request, String authorization, String requestId, VerifiedChatUser authenticatedUser) {
        return client.query(request, authorization, requestId, authenticatedUser)
                .timeout(properties.timeout())
                .onErrorMap(TimeoutException.class, ignored -> new ChatbotTimeoutException())
                .onErrorMap(
                        exception -> !(exception instanceof ChatbotTimeoutException)
                                && !(exception instanceof ChatbotProviderUnavailableException),
                        ignored -> new ChatbotProviderUnavailableException());
    }
}
