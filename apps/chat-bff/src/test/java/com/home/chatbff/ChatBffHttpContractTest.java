package com.home.chatbff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.home.chatbff.ai.ChatbotAiClient;
import com.home.chatbff.ai.ChatbotAiStreamEvent;
import com.home.chatbff.ai.ChatbotProviderUnavailableException;
import com.home.chatbff.auth.ChatUserAuthenticator;
import com.home.chatbff.auth.VerifiedChatUser;
import com.home.chatbff.ratelimit.ChatbotRateLimitUnavailableException;
import com.home.chatbff.ratelimit.ChatbotRateLimitedException;
import com.home.chatbff.ratelimit.ChatbotRateLimiter;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"home.chat-bff.ai.timeout=25ms", "home.chat-bff.ai.base-url=http://127.0.0.1:9"})
class ChatBffHttpContractTest {
    @LocalServerPort
    int port;

    WebTestClient client;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    ChatbotAiClient aiClient;

    @MockitoBean
    ChatUserAuthenticator authenticator;

    @MockitoBean
    ChatbotRateLimiter rateLimiter;

    @BeforeEach
    void authenticateTestToken() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(5))
                .build();
        when(authenticator.authenticate("Bearer valid-token")).thenReturn(new VerifiedChatUser(42L));
        when(rateLimiter.acquire(42L)).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("GET /health는 인증 없이 상태와 request ID를 반환한다")
    void healthIsPublicAndReturnsRequestId() {
        client.get()
                .uri("/health")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .exists("X-Request-Id")
                .expectBody()
                .json("{\"status\":\"ok\"}");
    }

    @Test
    @DisplayName("chatbot query는 누락된 JWT를 공통 ProblemDetail 401로 거부한다")
    void queryRejectsMissingJwt() {
        client.post()
                .uri("/api/v1/chatbot/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"question\":\"잠실엘스 최근 거래 알려줘\"}")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("AUTHENTICATION_REQUIRED")
                .jsonPath("$.requestId")
                .isNotEmpty();
    }

    @Test
    @DisplayName("subject 요청 한도 초과는 AI를 호출하지 않고 429로 거부한다")
    void queryRejectsRateLimitedSubject() {
        when(rateLimiter.acquire(42L)).thenReturn(Mono.error(new ChatbotRateLimitedException(60)));

        client.post()
                .uri("/api/v1/chatbot/query")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"question\":\"잠실엘스 최근 거래 알려줘\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(429)
                .expectHeader()
                .valueEquals(HttpHeaders.RETRY_AFTER, "60")
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("CHATBOT_RATE_LIMITED");

        verifyNoInteractions(aiClient);
    }

    @Test
    @DisplayName("Redis rate-limit guard 장애는 AI를 호출하지 않고 503 fail-closed한다")
    void queryFailsClosedWhenRateLimitGuardIsUnavailable() {
        when(rateLimiter.acquire(42L)).thenReturn(Mono.error(new ChatbotRateLimitUnavailableException()));

        client.post()
                .uri("/api/v1/chatbot/query/stream")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"question\":\"잠실엘스 최근 거래 알려줘\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("CHATBOT_RATE_LIMIT_UNAVAILABLE");

        verifyNoInteractions(aiClient);
    }

    @Test
    @DisplayName("admission 이후 BFF timeout은 질문 원문 없는 HTTP 200 safe final로 변환한다")
    void queryMapsTimeout() {
        when(aiClient.query(any(), anyString(), anyString(), any())).thenReturn(Mono.never());

        client.post()
                .uri("/api/v1/chatbot/query")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"question\":\"잠실엘스 최근 거래 알려줘\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.terminalOutcome.reason")
                .isEqualTo("TEMPORARY_FAILURE")
                .jsonPath("$.terminalOutcome.retryable")
                .isEqualTo(true)
                .jsonPath("$.answer")
                .value(answer -> assertThat(answer.toString()).doesNotContain("잠실엘스"));
    }

    @Test
    @DisplayName("admission 이후 AI provider 오류는 HTTP 200 safe final로 변환한다")
    void queryMapsProviderFailure() {
        when(aiClient.query(any(), anyString(), anyString(), any()))
                .thenReturn(Mono.error(new ChatbotProviderUnavailableException()));

        client.post()
                .uri("/api/v1/chatbot/query")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"question\":\"잠실엘스 최근 거래 알려줘\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.terminalOutcome.reason")
                .isEqualTo("TEMPORARY_FAILURE");
    }

    @Test
    @DisplayName("예상하지 못한 AI client 오류도 원문을 숨긴 safe final로 변환한다")
    void queryMapsUnexpectedFailure() {
        when(aiClient.query(any(), anyString(), anyString(), any()))
                .thenReturn(Mono.error(new IllegalStateException("internal provider detail")));

        client.post()
                .uri("/api/v1/chatbot/query")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"question\":\"잠실엘스 최근 거래 알려줘\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.terminalOutcome.reason")
                .isEqualTo("TEMPORARY_FAILURE")
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent()))
                        .doesNotContain("internal provider detail"));
    }

    @Test
    @DisplayName("Web 최소 계약을 충족하지 못한 AI JSON은 safe final로 변환한다")
    void queryMapsAnswerOnlyResponseToSafeFinal() throws Exception {
        when(aiClient.query(any(), anyString(), anyString(), any()))
                .thenReturn(Mono.just(objectMapper.readTree("{\"answer\":\"불완전 응답\"}")));

        client.post()
                .uri("/api/v1/chatbot/query")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"question\":\"잠실엘스 최근 거래 알려줘\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.terminalOutcome.reason")
                .isEqualTo("TEMPORARY_FAILURE")
                .jsonPath("$.answer")
                .value(answer -> assertThat(answer.toString()).doesNotContain("불완전 응답"));
    }

    @Test
    @DisplayName("시작된 SSE의 AI 오류는 answer_delta와 safe final 한 번으로 종료한다")
    void streamMapsProviderFailureToSafeFinal() {
        when(aiClient.stream(any(), anyString(), anyString(), any()))
                .thenReturn(Flux.error(new ChatbotProviderUnavailableException()));

        byte[] body = client.post()
                .uri("/api/v1/chatbot/query/stream")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"question\":\"잠실엘스 최근 거래 알려줘\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody()
                .returnResult()
                .getResponseBody();

        String events = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(events)
                .contains("event:answer_delta", "event:final", "TEMPORARY_FAILURE")
                .doesNotContain("event:error", "잠실엘스");
        assertThat(events.split("event:final", -1)).hasSize(2);
    }

    @Test
    @DisplayName("Web 최소 계약을 충족하지 못한 AI SSE final은 safe final 한 번으로 종료한다")
    void streamMapsAnswerOnlyFinalToSafeFinal() throws Exception {
        when(aiClient.stream(any(), anyString(), anyString(), any()))
                .thenReturn(
                        Flux.just(new ChatbotAiStreamEvent("final", objectMapper.readTree("{\"answer\":\"불완전 응답\"}"))));

        byte[] body = client.post()
                .uri("/api/v1/chatbot/query/stream")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"question\":\"잠실엘스 최근 거래 알려줘\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();

        String events = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(events)
                .contains("event:answer_delta", "event:final", "TEMPORARY_FAILURE")
                .doesNotContain("event:error", "불완전 응답");
        assertThat(events.split("event:final", -1)).hasSize(2);
    }

    @Test
    @DisplayName("성공한 SSE의 answer_delta 결합은 Unicode answer와 같고 final은 한 번이다")
    void streamReturnsExactAnswerDeltasBeforeOneFinalEvent() throws Exception {
        String answer = "검증된 실거래 답변🙂".repeat(40);
        var response = successfulResponse(answer);
        var status = objectMapper.createObjectNode();
        status.put("code", "EVIDENCE_COMPARISON");
        status.put("message", "질문 원문을 포함하면 안 됨: 잠실엘스");
        when(aiClient.stream(any(), anyString(), anyString(), any()))
                .thenReturn(Flux.just(
                        new ChatbotAiStreamEvent("status", status), new ChatbotAiStreamEvent("final", response)));

        byte[] body = client.post()
                .uri("/api/v1/chatbot/query/stream")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"question\":\"잠실엘스 최근 거래 알려줘\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();

        String events = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        String currentEvent = "";
        StringBuilder combinedDeltas = new StringBuilder();
        int deltaCount = 0;
        int finalCount = 0;
        for (String line : events.split("\\R")) {
            if (line.startsWith("event:")) {
                currentEvent = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                JsonNode data =
                        objectMapper.readTree(line.substring("data:".length()).trim());
                if (currentEvent.equals("answer_delta")) {
                    combinedDeltas.append(data.get("delta").asText());
                    deltaCount++;
                } else if (currentEvent.equals("final")) {
                    assertThat(data.at("/response/answer").asText()).isEqualTo(answer);
                    finalCount++;
                }
            }
        }
        assertThat(deltaCount).isGreaterThan(1);
        assertThat(combinedDeltas.toString()).isEqualTo(answer);
        assertThat(finalCount).isEqualTo(1);
        assertThat(events).contains("event:status", "근거 비교").doesNotContain("event:error", "잠실엘스");
    }

    @Test
    @DisplayName("focusComplex/v1은 JSON 응답에서 business 재선택 없이 그대로 전달한다")
    void queryPassesThroughFocusComplexAction() {
        JsonNode response = responseWithFocusComplexAction();
        when(aiClient.query(any(), anyString(), anyString(), any())).thenReturn(Mono.just(response));

        client.post()
                .uri("/api/v1/chatbot/query")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"question\":\"마포래미안푸르지오 최근 거래\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.uiActions[0].type")
                .isEqualTo("focusComplex")
                .jsonPath("$.uiActions[0].parcelId")
                .isEqualTo(8015)
                .jsonPath("$.uiActions[0].complexId")
                .isEqualTo(7756)
                .jsonPath("$.uiActions[0].autoRun")
                .isEqualTo(true)
                .jsonPath("$.uiActions[0].factIds[0]")
                .isEqualTo("property-complex-7756");
    }

    @Test
    @DisplayName("focusComplex/v1은 SSE final에도 JSON과 같은 shape로 전달한다")
    void streamPassesThroughFocusComplexAction() throws Exception {
        JsonNode response = responseWithFocusComplexAction();
        when(aiClient.stream(any(), anyString(), anyString(), any()))
                .thenReturn(Flux.just(new ChatbotAiStreamEvent("final", response)));

        byte[] body = client.post()
                .uri("/api/v1/chatbot/query/stream")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"question\":\"마포래미안푸르지오 최근 거래\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();

        String events = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        JsonNode finalData = null;
        String currentEvent = "";
        for (String line : events.split("\\R")) {
            if (line.startsWith("event:")) currentEvent = line.substring(6).trim();
            else if (line.startsWith("data:") && currentEvent.equals("final")) {
                finalData = objectMapper.readTree(line.substring(5).trim()).get("response");
            }
        }
        assertThat(finalData).isNotNull();
        assertThat(finalData.get("uiActions")).isEqualTo(response.get("uiActions"));
    }

    @Test
    @DisplayName("시작된 SSE의 timeout은 safe final 한 번으로 종료한다")
    void streamMapsTimeoutToSafeFinal() {
        when(aiClient.stream(any(), anyString(), anyString(), any())).thenReturn(Flux.never());

        byte[] body = client.post()
                .uri("/api/v1/chatbot/query/stream")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"question\":\"잠실엘스 최근 거래 알려줘\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();

        String events = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(events)
                .contains("event:answer_delta", "event:final", "TEMPORARY_FAILURE")
                .doesNotContain("event:error", "잠실엘스");
    }

    @Test
    @DisplayName("예상하지 못한 생성 오류도 비노출 safe final로 종료한다")
    void streamMapsUnexpectedFailureToSafeFinal() {
        when(aiClient.stream(any(), anyString(), anyString(), any()))
                .thenReturn(Flux.error(new IllegalStateException("internal provider detail")));

        byte[] body = client.post()
                .uri("/api/v1/chatbot/query/stream")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"question\":\"잠실엘스 최근 거래 알려줘\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();

        String events = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(events)
                .contains("event:final", "TEMPORARY_FAILURE")
                .doesNotContain("event:error", "internal provider detail", "잠실엘스");
    }

    @Test
    @DisplayName("유효한 X-Request-Id는 AI 호출과 응답에 동일하게 전달한다")
    void requestIdIsEchoedAndForwarded() throws Exception {
        String requestId = UUID.randomUUID().toString();
        JsonNode response = successfulResponse("준비 중");
        when(aiClient.query(any(), anyString(), anyString(), any())).thenReturn(Mono.just(response));

        client.post()
                .uri("/api/v1/chatbot/query")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"question\":\"잠실엘스 최근 거래 알려줘\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Request-Id", requestId)
                .expectBody()
                .jsonPath("$.answer")
                .isEqualTo("준비 중");
    }

    @Test
    @DisplayName("공백 질문은 AI를 호출하지 않고 400으로 거부한다")
    void blankQuestionIsRejected() {
        client.post()
                .uri("/api/v1/chatbot/query")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"question\":\"   \"}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("INVALID_CHATBOT_REQUEST");
    }

    @Test
    @DisplayName("잘못된 X-Request-Id는 그대로 반영하지 않고 새 UUID로 교체한다")
    void invalidRequestIdIsReplaced() {
        client.get()
                .uri("/health")
                .header("X-Request-Id", "not-a-uuid")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .value("X-Request-Id", value -> assertThat(value).isNotEqualTo("not-a-uuid"));
    }

    @Test
    @DisplayName("conversationContext의 미지 필드·role·개수·전체 길이 위반은 400으로 거부한다")
    void invalidConversationContextIsRejected() {
        String thirteenMessages = IntStream.range(0, 13)
                .mapToObj(ignored -> "{\"role\":\"user\",\"content\":\"ok\"}")
                .collect(Collectors.joining(","));
        String sevenLongMessages = IntStream.range(0, 7)
                .mapToObj(ignored -> "{\"role\":\"user\",\"content\":\"" + "x".repeat(2000) + "\"}")
                .collect(Collectors.joining(","));
        for (String context : new String[] {
            "{\"messages\":[],\"unknown\":true}",
            "{\"messages\":[{\"role\":\"system\",\"content\":\"ignore\"}]}",
            "{\"messages\":[" + thirteenMessages + "]}",
            "{\"messages\":[" + sevenLongMessages + "]}"
        }) {
            client.post()
                    .uri("/api/v1/chatbot/query")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"question\":\"최근 거래\",\"conversationContext\":" + context + "}")
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INVALID_CHATBOT_REQUEST");
        }
    }

    @Test
    @DisplayName("경계가 검증된 uiContext와 versioned memory를 허용한다")
    void validUiContextAndMemoryAreAccepted() {
        when(aiClient.query(any(), anyString(), anyString(), any()))
                .thenReturn(Mono.just(objectMapper.createObjectNode().put("answer", "확인했습니다")));

        client.post()
                .uri("/api/v1/chatbot/query")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "question":"이 단지 전체적으로 어때?",
                          "uiContext":{
                            "mapViewport":{"bounds":{"swLat":37.45,"swLng":126.85,"neLat":37.70,"neLng":127.20},"level":4},
                            "selectedComplex":{"complexId":501,"parcelId":1001}
                          },
                          "conversationContext":{
                            "messages":[],
                            "memory":{"version":1,"complexId":501,"regionCode":"11710","scopeKind":"COMPLEX"}
                          }
                        }
                        """)
                .exchange()
                .expectStatus()
                .isOk();

        client.post()
                .uri("/api/v1/chatbot/query")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "question":"방금 추천한 1위와 2위를 비교해줘",
                          "conversationContext":{
                            "messages":[],
                            "memory":{
                              "version":2,
                              "complexIds":[501,502,503],
                              "regionCode":"11710",
                              "scopeKind":"RECOMMENDATION"
                            }
                          }
                        }
                        """)
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @DisplayName("잘못된 uiContext와 memory는 AI를 호출하지 않고 400으로 거부한다")
    void invalidUiContextAndMemoryAreRejected() {
        for (String extra : new String[] {
            "\"uiContext\":{}",
            "\"uiContext\":{\"selectedComplex\":{\"complexId\":501}}",
            "\"uiContext\":{\"selectedComplex\":{\"complexId\":\"501\",\"parcelId\":1001}}",
            "\"uiContext\":{\"mapViewport\":{\"bounds\":{\"swLat\":\"37.45\",\"swLng\":126.85,\"neLat\":37.70,\"neLng\":127.20},\"level\":\"4\"}}",
            "\"uiContext\":{\"mapViewport\":{\"bounds\":{\"swLat\":37.7,\"swLng\":127.2,\"neLat\":37.4,\"neLng\":126.8},\"level\":4}}",
            "\"conversationContext\":{\"memory\":{\"version\":2,\"scopeKind\":\"MAP_VIEWPORT\"}}",
            "\"conversationContext\":{\"memory\":{\"version\":2,\"complexIds\":[501],\"scopeKind\":\"RECOMMENDATION\"}}",
            "\"conversationContext\":{\"memory\":{\"version\":2,\"complexIds\":[501,501],\"scopeKind\":\"RECOMMENDATION\"}}",
            "\"conversationContext\":{\"memory\":{\"version\":2,\"complexId\":501,\"complexIds\":[501,502],\"scopeKind\":\"RECOMMENDATION\"}}",
            "\"conversationContext\":{\"memory\":{\"version\":\"1\",\"scopeKind\":\"MAP_VIEWPORT\"}}",
            "\"conversationContext\":{\"memory\":{\"version\":1,\"scopeKind\":\"COMPLEX\"}}"
        }) {
            client.post()
                    .uri("/api/v1/chatbot/query")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"question\":\"최근 거래\"," + extra + "}")
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INVALID_CHATBOT_REQUEST");
        }
    }

    private ObjectNode successfulResponse(String answer) {
        var response = objectMapper.createObjectNode();
        response.put("success", true);
        response.put("status", "success");
        response.put("answer", answer);
        response.set("citations", objectMapper.createArrayNode());
        response.putNull("dataAsOf");
        response.set("limitations", objectMapper.createArrayNode());
        var evidence = objectMapper.createObjectNode();
        evidence.put("status", "supported");
        evidence.set("capabilities", objectMapper.createArrayNode());
        evidence.put("factCount", 0);
        evidence.put("citationCount", 0);
        response.set("evidenceSummary", evidence);
        return response;
    }

    private ObjectNode responseWithFocusComplexAction() {
        var response = successfulResponse("대표 단지의 실거래를 확인했습니다.");
        var citation = objectMapper.createObjectNode();
        citation.put("citationId", "citation-property-complex-7756");
        citation.put("sourceId", "property-complex");
        citation.put("sourceName", "Home Search 단지");
        citation.putNull("sourceUrl");
        citation.put("evidenceGrade", "A");
        citation.put("datasetVersion", "property-current");
        citation.put("dataAsOf", "2026-08-07");
        citation.putNull("observedAt");
        citation.putArray("factIds").add("property-complex-7756");
        response.withArray("citations").add(citation);
        response.put("dataAsOf", "2026-08-07");
        response.withObject("evidenceSummary").put("factCount", 1).put("citationCount", 1);
        var action = objectMapper.createObjectNode();
        action.put("type", "focusComplex");
        action.put("version", 1);
        action.put("actionId", "action-request-focus-complex-7756");
        action.put("label", "마포래미안푸르지오4단지 지도에서 보기");
        action.put("parcelId", 8015);
        action.put("complexId", 7756);
        action.putObject("center").put("lat", 37.5555141).put("lng", 126.9537536);
        action.put("level", 4);
        action.put("openDetail", true);
        action.put("autoRun", true);
        action.putArray("factIds").add("property-complex-7756");
        response.putArray("uiActions").add(action);
        return response;
    }
}
