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
    @DisplayName("AI SSE의 제한된 status와 final 응답을 순서대로 전달한다")
    void forwardsUpstreamStreamEvents() {
        AtomicReference<String> path = new AtomicReference<>();
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    path.set(request.uri());
                    return response.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                            .sendString(Mono.just("""
                                    event: status
                                    data: {"requestId":"request-1","code":"CANDIDATE_CHECK","message":"후보 확인"}

                                    event: final
                                    data: {"requestId":"request-1","response":{"answer":"완료"}}

                                    """));
                })
                .bindNow();
        var client = client(URI.create("http://127.0.0.1:" + server.port()));

        var events = client.stream(
                        new ChatbotQueryRequest("추천", null),
                        "Bearer user-token",
                        "request-1",
                        new VerifiedChatUser(42L))
                .collectList()
                .block(Duration.ofSeconds(3));

        assertThat(path).hasValue("/api/v1/chatbot/query/stream");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).event()).isEqualTo("status");
        assertThat(events.get(0).data().path("code").asText()).isEqualTo("CANDIDATE_CHECK");
        assertThat(events.get(1).event()).isEqualTo("final");
        assertThat(events.get(1).data().path("answer").asText()).isEqualTo("완료");
    }

    @Test
    @DisplayName("AI HTTP, 연결 실패, malformed JSON을 서로 다른 내부 outcome으로 보존한다")
    void classifiesHttpConnectionAndMalformedJson() {
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> response.status(503).send())
                .bindNow();

        assertThatThrownBy(() -> query(client(URI.create("http://127.0.0.1:" + server.port()))))
                .isInstanceOfSatisfying(
                        ChatbotUpstreamHttpException.class,
                        exception -> assertThat(exception.statusCode()).isEqualTo(503));
        assertProviderUnavailable(client(URI.create("http://127.0.0.1:1")));

        server.disposeNow();
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> response.status(400).send())
                .bindNow();
        assertThatThrownBy(() -> query(client(URI.create("http://127.0.0.1:" + server.port()))))
                .isInstanceOfSatisfying(
                        ChatbotUpstreamHttpException.class,
                        exception -> assertThat(exception.statusCode()).isEqualTo(400));

        server.disposeNow();
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> response.header(
                                HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .sendString(Mono.just("[]")))
                .bindNow();
        assertThatThrownBy(() -> query(client(URI.create("http://127.0.0.1:" + server.port()))))
                .isExactlyInstanceOf(ChatbotInvalidJsonException.class);
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
        assertThatThrownBy(() -> query(client)).isExactlyInstanceOf(ChatbotProviderUnavailableException.class);
    }

    private static JsonNode query(WebClientChatbotAiClient client) {
        return client.query(
                        new ChatbotQueryRequest("최근 거래", null), "Bearer token", "request-1", new VerifiedChatUser(42L))
                .block(Duration.ofSeconds(3));
    }
}
