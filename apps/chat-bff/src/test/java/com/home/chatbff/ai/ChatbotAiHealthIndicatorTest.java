package com.home.chatbff.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

class ChatbotAiHealthIndicatorTest {
    private DisposableServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.disposeNow();
    }

    @Test
    void readyAndDegradedAiAreBothBffReady() {
        server = jsonServer("DEGRADED");
        var indicator = indicator(URI.create("http://127.0.0.1:" + server.port()));

        var health = indicator.health().block(Duration.ofSeconds(3));

        assertThat(health).isNotNull();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("status", "DEGRADED");
    }

    @Test
    void unreachableOrNotReadyAiMakesBffReadinessDown() {
        var unreachable = indicator(URI.create("http://127.0.0.1:1")).health().block(Duration.ofSeconds(4));
        assertThat(unreachable).isNotNull();
        assertThat(unreachable.getStatus().getCode()).isEqualTo("DOWN");

        server = jsonServer("NOT_READY");
        var notReady = indicator(URI.create("http://127.0.0.1:" + server.port()))
                .health()
                .block(Duration.ofSeconds(4));
        assertThat(notReady).isNotNull();
        assertThat(notReady.getStatus().getCode()).isEqualTo("DOWN");
    }

    private DisposableServer jsonServer(String readiness) {
        return HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> response.header(
                                HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .sendString(Mono.just(
                                request.uri().equals("/health")
                                        ? "{\"status\":\"ok\"}"
                                        : "{\"status\":\"" + readiness + "\",\"checks\":{\"property\":\"ready\"}}")))
                .bindNow();
    }

    private ChatbotAiHealthIndicator indicator(URI baseUrl) {
        return new ChatbotAiHealthIndicator(
                WebClient.builder(), new ChatbotAiProperties(baseUrl, Duration.ofSeconds(70)));
    }
}
