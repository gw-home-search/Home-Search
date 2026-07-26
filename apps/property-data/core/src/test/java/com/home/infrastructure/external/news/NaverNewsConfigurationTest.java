package com.home.infrastructure.external.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.news.collection.NewsProviderQuery;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NaverNewsConfigurationTest {

    @Test
    @DisplayName("NAVER API HUB endpoint에는 NCP API Gateway credential header만 전송한다")
    void sendsNaverApiHubCredentialHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<com.sun.net.httpserver.Headers> receivedHeaders = new AtomicReference<>();
        server.createContext("/search/v1/news", exchange -> {
            receivedHeaders.set(exchange.getRequestHeaders());
            byte[] response = """
                {"total":0,"start":1,"display":0,"items":[]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            NaverNewsProperties properties = new NaverNewsProperties(
                    true,
                    NaverNewsProviderMode.API_HUB,
                    "api-hub-client-id",
                    "api-hub-client-secret",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "/search/v1/news",
                    4000,
                    false,
                    Duration.ofDays(31),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5));
            var gateway = new NaverNewsConfiguration().naverNewsProviderGateway(properties, new ObjectMapper());

            gateway.search(new NewsProviderQuery("아파트", 1, 100));

            assertThat(receivedHeaders.get().getFirst("X-NCP-APIGW-API-KEY-ID")).isEqualTo("api-hub-client-id");
            assertThat(receivedHeaders.get().getFirst("X-NCP-APIGW-API-KEY")).isEqualTo("api-hub-client-secret");
            assertThat(receivedHeaders.get().getFirst("X-Naver-Client-Id")).isNull();
            assertThat(receivedHeaders.get().getFirst("X-Naver-Client-Secret")).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("NAVER Developers endpoint에는 Developers credential header만 전송한다")
    void sendsNaverDevelopersCredentialHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<com.sun.net.httpserver.Headers> receivedHeaders = new AtomicReference<>();
        server.createContext("/v1/search/news.json", exchange -> {
            receivedHeaders.set(exchange.getRequestHeaders());
            byte[] response = """
                {"total":0,"start":1,"display":0,"items":[]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            NaverNewsProperties properties = new NaverNewsProperties(
                    true,
                    NaverNewsProviderMode.DEVELOPERS,
                    "developers-client-id",
                    "developers-client-secret",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "/v1/search/news.json",
                    4000,
                    false,
                    Duration.ofDays(31),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5));
            var gateway = new NaverNewsConfiguration().naverNewsProviderGateway(properties, new ObjectMapper());

            gateway.search(new NewsProviderQuery("아파트", 1, 100));

            assertThat(receivedHeaders.get().getFirst("X-Naver-Client-Id")).isEqualTo("developers-client-id");
            assertThat(receivedHeaders.get().getFirst("X-Naver-Client-Secret")).isEqualTo("developers-client-secret");
            assertThat(receivedHeaders.get().getFirst("X-NCP-APIGW-API-KEY-ID")).isNull();
            assertThat(receivedHeaders.get().getFirst("X-NCP-APIGW-API-KEY")).isNull();
        } finally {
            server.stop(0);
        }
    }
}
