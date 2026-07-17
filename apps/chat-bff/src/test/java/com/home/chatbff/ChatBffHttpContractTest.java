package com.home.chatbff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.home.chatbff.ai.ChatbotAiClient;
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
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
    @DisplayName("BFF timeout은 질문 원문을 노출하지 않는 504 ProblemDetail로 변환한다")
    void queryMapsTimeout() {
        when(aiClient.query(any(), anyString(), anyString(), any())).thenReturn(Mono.never());

        client.post()
                .uri("/api/v1/chatbot/query")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"question\":\"잠실엘스 최근 거래 알려줘\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(504)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("CHATBOT_TIMEOUT")
                .jsonPath("$.detail")
                .value(detail -> assertThat(detail.toString()).doesNotContain("잠실엘스"));
    }

    @Test
    @DisplayName("AI provider 오류는 503 ProblemDetail로 변환한다")
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
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("CHATBOT_PROVIDER_UNAVAILABLE");
    }

    @Test
    @DisplayName("예상하지 못한 AI client 오류도 원문을 숨긴 503으로 변환한다")
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
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("CHATBOT_PROVIDER_UNAVAILABLE")
                .jsonPath("$.detail")
                .value(detail -> assertThat(detail.toString()).doesNotContain("internal provider detail"));
    }

    @Test
    @DisplayName("시작된 SSE의 AI 오류는 final 없이 명시적인 error event로 종료한다")
    void streamMapsProviderFailureToErrorEvent() {
        when(aiClient.query(any(), anyString(), anyString(), any()))
                .thenReturn(Mono.error(new ChatbotProviderUnavailableException()));

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
                .contains("event:error", "CHATBOT_PROVIDER_UNAVAILABLE")
                .doesNotContain("event:final");
    }

    @Test
    @DisplayName("성공한 SSE는 request ID를 포함한 final event 하나만 반환한다")
    void streamReturnsFinalEventAfterSuccessfulResponse() throws Exception {
        JsonNode response = objectMapper.readTree("{\"answer\":\"준비 중\"}");
        when(aiClient.query(any(), anyString(), anyString(), any())).thenReturn(Mono.just(response));

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
        assertThat(events).contains("event:final", "requestId", "준비 중").doesNotContain("event:error");
    }

    @Test
    @DisplayName("시작된 SSE의 timeout은 final 없이 CHATBOT_TIMEOUT error event로 종료한다")
    void streamMapsTimeoutToErrorEvent() {
        when(aiClient.query(any(), anyString(), anyString(), any())).thenReturn(Mono.never());

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
        assertThat(events).contains("event:error", "CHATBOT_TIMEOUT").doesNotContain("event:final");
    }

    @Test
    @DisplayName("예상하지 못한 생성 오류도 final 없이 비노출 error event로 종료한다")
    void streamMapsUnexpectedFailureToErrorEvent() {
        when(aiClient.query(any(), anyString(), anyString(), any()))
                .thenReturn(Mono.error(new IllegalStateException("internal provider detail")));

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
                .contains("event:error", "CHATBOT_PROVIDER_UNAVAILABLE")
                .doesNotContain("event:final", "internal provider detail");
    }

    @Test
    @DisplayName("유효한 X-Request-Id는 AI 호출과 응답에 동일하게 전달한다")
    void requestIdIsEchoedAndForwarded() throws Exception {
        String requestId = UUID.randomUUID().toString();
        JsonNode response = objectMapper.readTree("{\"success\":false,\"status\":\"failed\",\"answer\":\"준비 중\"}");
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
}
