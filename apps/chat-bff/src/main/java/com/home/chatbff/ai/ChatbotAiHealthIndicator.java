package com.home.chatbff.ai;

import java.time.Duration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@Component("chatbotAiHealthIndicator")
final class ChatbotAiHealthIndicator implements ReactiveHealthIndicator {
    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(3);
    private final WebClient client;

    ChatbotAiHealthIndicator(WebClient.Builder builder, ChatbotAiProperties properties) {
        this.client = builder.baseUrl(properties.baseUrl().toString()).build();
    }

    @Override
    public Mono<Health> health() {
        return client.get()
                .uri("/health")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .filter(body -> "ok".equals(body.path("status").asText()))
                .switchIfEmpty(Mono.error(new IllegalStateException("AI liveness check failed")))
                .then(client.get().uri("/ready").retrieve().bodyToMono(JsonNode.class))
                .filter(body -> {
                    String status = body.path("status").asText();
                    return "READY".equals(status) || "DEGRADED".equals(status);
                })
                .map(body -> Health.up()
                        .withDetail("status", body.path("status").asText())
                        .withDetail("checks", body.path("checks"))
                        .build())
                .switchIfEmpty(Mono.just(
                        Health.down().withDetail("status", "NOT_READY").build()))
                .timeout(READINESS_TIMEOUT)
                .onErrorResume(ignored -> Mono.just(
                        Health.down().withDetail("status", "UNREACHABLE").build()));
    }
}
