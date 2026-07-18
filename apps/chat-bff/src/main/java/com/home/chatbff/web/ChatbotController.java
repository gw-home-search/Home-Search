package com.home.chatbff.web;

import com.home.chatbff.ai.ChatbotGateway;
import com.home.chatbff.ai.ChatbotProviderUnavailableException;
import com.home.chatbff.ai.ChatbotTimeoutException;
import com.home.chatbff.auth.VerifiedChatUser;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@RestController
final class ChatbotController {
    private static final int ANSWER_DELTA_CODE_POINTS = 128;

    private final ChatbotGateway gateway;
    private final ObjectMapper objectMapper;

    ChatbotController(ChatbotGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/api/v1/chatbot/query")
    Mono<JsonNode> query(
            @Valid @RequestBody ChatbotQueryRequest request,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            ServerWebExchange exchange) {
        String requestId = RequestIdWebFilter.required(exchange);
        return gateway.query(request, authorization, requestId, authenticatedUser(exchange))
                .map(response -> withRequestId(response, requestId));
    }

    @PostMapping(value = "/api/v1/chatbot/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<JsonNode>> stream(
            @Valid @RequestBody ChatbotQueryRequest request,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            ServerWebExchange exchange) {
        String requestId = RequestIdWebFilter.required(exchange);
        return gateway.query(request, authorization, requestId, authenticatedUser(exchange))
                .map(response -> withRequestId(response, requestId))
                .flatMapMany(response -> successEvents(requestId, response))
                .onErrorResume(
                        ChatbotProviderUnavailableException.class,
                        ignored -> Flux.just(errorEvent(requestId, "CHATBOT_PROVIDER_UNAVAILABLE", "답변을 생성하지 못했습니다.")))
                .onErrorResume(
                        ChatbotTimeoutException.class,
                        ignored -> Flux.just(errorEvent(requestId, "CHATBOT_TIMEOUT", "답변 생성 시간이 초과되었습니다.")))
                .onErrorResume(
                        RuntimeException.class,
                        ignored -> Flux.just(errorEvent(requestId, "CHATBOT_PROVIDER_UNAVAILABLE", "답변을 생성하지 못했습니다.")));
    }

    private VerifiedChatUser authenticatedUser(ServerWebExchange exchange) {
        VerifiedChatUser user = exchange.getAttribute(ChatbotAuthenticationWebFilter.USER_ATTRIBUTE);
        if (user == null) throw new IllegalStateException("authenticated user missing");
        return user;
    }

    private JsonNode withRequestId(JsonNode response, String requestId) {
        if (response instanceof ObjectNode object) object.put("requestId", requestId);
        return response;
    }

    private ObjectNode finalData(String requestId, JsonNode response) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("requestId", requestId);
        data.set("response", response);
        return data;
    }

    private Flux<ServerSentEvent<JsonNode>> successEvents(String requestId, JsonNode response) {
        JsonNode answerNode = response.get("answer");
        if (answerNode == null || !answerNode.isTextual() || answerNode.asText().isBlank()) {
            return Flux.error(new ChatbotProviderUnavailableException());
        }
        String answer = answerNode.asText();
        List<ServerSentEvent<JsonNode>> events = new ArrayList<>();
        int start = 0;
        while (start < answer.length()) {
            int end = answer.offsetByCodePoints(
                    start, Math.min(ANSWER_DELTA_CODE_POINTS, answer.codePointCount(start, answer.length())));
            ObjectNode data = objectMapper.createObjectNode();
            data.put("requestId", requestId);
            data.put("delta", answer.substring(start, end));
            events.add(event("answer_delta", data));
            start = end;
        }
        events.add(event("final", finalData(requestId, response)));
        return Flux.fromIterable(events);
    }

    private ServerSentEvent<JsonNode> errorEvent(String requestId, String code, String message) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("requestId", requestId);
        data.put("code", code);
        data.put("message", message);
        return event("error", data);
    }

    private ServerSentEvent<JsonNode> event(String name, JsonNode data) {
        return ServerSentEvent.<JsonNode>builder().event(name).data(data).build();
    }
}
