package com.home.chatbff.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.chatbff.auth.VerifiedChatUser;
import com.home.chatbff.web.ChatbotQueryRequest;
import com.home.chatbff.web.ChatbotQueryRequest.ConversationContext;
import com.home.chatbff.web.ChatbotQueryRequest.ConversationMemory;
import com.home.chatbff.web.ChatbotQueryRequest.ConversationMessage;
import com.home.chatbff.web.ChatbotQueryRequest.MapBounds;
import com.home.chatbff.web.ChatbotQueryRequest.MapViewport;
import com.home.chatbff.web.ChatbotQueryRequest.ScopeKind;
import com.home.chatbff.web.ChatbotQueryRequest.SelectedComplex;
import com.home.chatbff.web.ChatbotQueryRequest.UiContext;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.JsonNode;

class WebClientChatbotAiClientTest {
    private DisposableServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.disposeNow();
    }

    @Test
    @DisplayName("AI 요청에 JWT와 request ID를 전달하고 JSON 응답을 반환한다")
    void forwardsHeadersAndReturnsJson() {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestId = new AtomicReference<>();
        AtomicReference<String> forwardedBody = new AtomicReference<>();
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    authorization.set(request.requestHeaders().get(HttpHeaders.AUTHORIZATION));
                    requestId.set(request.requestHeaders().get("X-Request-Id"));
                    return request.receive().aggregate().asString().flatMap(body -> {
                        forwardedBody.set(body);
                        return response.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .sendString(Mono.just("""
                                        {"answer":"준비 중","uiSummary":{"version":1,
                                        "scopeNotice":null,
                                        "headline":{"text":"최근 거래를 확인했습니다.","factIds":["fact-1"]},
                                        "criteria":[],"interpretations":[],"followUp":null,
                                        "fragmentSummaries":[]}}
                                        """))
                                .then();
                    });
                })
                .bindNow();
        var client = client(URI.create("http://127.0.0.1:" + server.port()));

        JsonNode result = client.query(
                        new ChatbotQueryRequest(
                                "최근 거래",
                                new UiContext(
                                        new MapViewport(new MapBounds(37.45, 126.85, 37.70, 127.20), 4),
                                        new SelectedComplex(501L, 1001L)),
                                new ConversationContext(
                                        List.of(new ConversationMessage("user", "이전 질문")),
                                        new ConversationMemory(1, 501L, "11710", ScopeKind.COMPLEX))),
                        "Bearer user-token",
                        "request-1",
                        new VerifiedChatUser(42L))
                .block(Duration.ofSeconds(3));

        assertThat(result.path("answer").asText()).isEqualTo("준비 중");
        assertThat(result.path("uiSummary").path("version").asInt()).isEqualTo(1);
        assertThat(result.path("uiSummary").path("headline").path("text").asText())
                .isEqualTo("최근 거래를 확인했습니다.");
        assertThat(authorization).hasValue("Bearer user-token");
        assertThat(requestId).hasValue("request-1");
        assertThat(forwardedBody.get())
                .contains("\"uiContext\"")
                .contains("\"complexId\":501")
                .contains("\"parcelId\":1001")
                .contains("\"memory\"")
                .contains("\"scopeKind\":\"COMPLEX\"");
    }

    @Test
    @DisplayName("AI 5xx와 연결 실패는 provider unavailable로 통일한다")
    void mapsHttpAndConnectionErrors() {
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> response.status(503).send())
                .bindNow();

        assertProviderUnavailable(client(URI.create("http://127.0.0.1:" + server.port())));
        assertProviderUnavailable(client(URI.create("http://127.0.0.1:1")));

        server.disposeNow();
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> response.header(
                                HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .sendString(Mono.just("[]")))
                .bindNow();
        assertProviderUnavailable(client(URI.create("http://127.0.0.1:" + server.port())));
    }

    @Test
    @DisplayName("인증 사용자가 없으면 외부 AI를 호출하지 않고 fail-closed한다")
    void rejectsMissingAuthenticatedUser() {
        WebClientChatbotAiClient client = client(URI.create("http://127.0.0.1:1"));

        assertThatThrownBy(() -> client.query(new ChatbotQueryRequest("최근 거래", null), "Bearer token", "request-1", null)
                        .block(Duration.ofSeconds(3)))
                .isExactlyInstanceOf(ChatbotProviderUnavailableException.class);
    }

    @Test
    @DisplayName("AI base URL과 timeout 설정은 필수이고 timeout은 양수여야 한다")
    void validatesAiProperties() {
        assertThatThrownBy(() -> new ChatbotAiProperties(null, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatbotAiProperties(URI.create("http://localhost"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatbotAiProperties(URI.create("http://localhost"), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatbotAiProperties(URI.create("http://localhost"), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static WebClientChatbotAiClient client(URI baseUrl) {
        return new WebClientChatbotAiClient(
                WebClient.builder(), new ChatbotAiProperties(baseUrl, Duration.ofSeconds(1)));
    }

    private static void assertProviderUnavailable(WebClientChatbotAiClient client) {
        assertThatThrownBy(() -> client.query(
                                new ChatbotQueryRequest("최근 거래", null),
                                "Bearer token",
                                "request-1",
                                new VerifiedChatUser(42L))
                        .block(Duration.ofSeconds(3)))
                .isExactlyInstanceOf(ChatbotProviderUnavailableException.class);
    }
}
