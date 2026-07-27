package com.home.chatbff.web;

import com.home.chatbff.ai.ChatbotAiStreamEvent;
import com.home.chatbff.ai.ChatbotGateway;
import com.home.chatbff.ai.ChatbotProviderUnavailableException;
import com.home.chatbff.auth.VerifiedChatUser;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final Set<String> STATUS_CODES = Set.of(
            "QUESTION_INTERPRETATION",
            "CANDIDATE_CHECK",
            "EVIDENCE_COMPARISON",
            "OFFICIAL_SOURCE_CHECK",
            "ANSWER_VALIDATION");

    private final ChatbotGateway gateway;
    private final ObjectMapper objectMapper;
    private final SafeFinalResponseFactory safeFinalResponseFactory;
    private final ChatbotResponseValidator responseValidator;

    ChatbotController(
            ChatbotGateway gateway,
            ObjectMapper objectMapper,
            SafeFinalResponseFactory safeFinalResponseFactory,
            ChatbotResponseValidator responseValidator) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.safeFinalResponseFactory = safeFinalResponseFactory;
        this.responseValidator = responseValidator;
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
                .map(response -> validated(response, requestId))
                .onErrorResume(
                        RuntimeException.class,
                        ignored -> Mono.just(safeFinalResponseFactory.create(requestId, "json_runtime")));
    }

    @PostMapping(value = "/api/v1/chatbot/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<JsonNode>> stream(
            @Valid @RequestBody ChatbotQueryRequest request,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            ServerWebExchange exchange) {
        String requestId = RequestIdWebFilter.required(exchange);
        AtomicBoolean finalSeen = new AtomicBoolean(false);
        return gateway.stream(request, authorization, requestId, authenticatedUser(exchange))
                .takeUntil(upstream ->
                        upstream.event().equals("final") || upstream.event().equals("error"))
                .concatMap(upstream -> publicEvents(requestId, upstream, finalSeen))
                .concatWith(Flux.defer(() -> finalSeen.get()
                        ? Flux.empty()
                        : successEvents(requestId, safeFinalResponseFactory.create(requestId, "missing_final"))))
                .onErrorResume(
                        RuntimeException.class,
                        ignored ->
                                successEvents(requestId, safeFinalResponseFactory.create(requestId, "stream_runtime")));
    }

    private Flux<ServerSentEvent<JsonNode>> publicEvents(
            String requestId, ChatbotAiStreamEvent upstream, AtomicBoolean finalSeen) {
        if (upstream.event().equals("status")) {
            JsonNode code = upstream.data().get("code");
            if (code == null || !code.isTextual() || !STATUS_CODES.contains(code.asText())) {
                return Flux.error(new ChatbotProviderUnavailableException());
            }
            ObjectNode data = objectMapper.createObjectNode();
            data.put("requestId", requestId);
            data.put("code", code.asText());
            data.put("message", statusMessage(code.asText()));
            return Flux.just(event("status", data));
        }
        if (upstream.event().equals("final")) {
            JsonNode response = validated(upstream.data(), requestId);
            finalSeen.set(true);
            return successEvents(requestId, response);
        }
        if (upstream.event().equals("error")) {
            return Flux.error(new ChatbotProviderUnavailableException());
        }
        return Flux.empty();
    }

    private String statusMessage(String code) {
        return switch (code) {
            case "QUESTION_INTERPRETATION" -> "질문 해석";
            case "CANDIDATE_CHECK" -> "후보 확인";
            case "EVIDENCE_COMPARISON" -> "근거 비교";
            case "OFFICIAL_SOURCE_CHECK" -> "공식 자료 확인";
            case "ANSWER_VALIDATION" -> "답변 검증";
            default -> throw new IllegalArgumentException("unsupported status code");
        };
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

    private JsonNode validated(JsonNode response, String requestId) {
        if (!responseValidator.isValid(response)) {
            throw new ChatbotProviderUnavailableException();
        }
        return withRequestId(response, requestId);
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

    private ServerSentEvent<JsonNode> event(String name, JsonNode data) {
        return ServerSentEvent.<JsonNode>builder().event(name).data(data).build();
    }
}
