package com.home.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.home.admin.security.AdminPrincipal;
import com.home.security.jwt.JwtVerificationPolicy;
import com.home.security.jwt.Rs256JwtCodec;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientPropertyAdminClientTest {
    @Test
    void sendsSignedActorRequestAndPreservesDownstreamProblemResponse() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var keys = generator.generateKeyPair();
        var codec = new Rs256JwtCodec(Clock.systemUTC());
        var issuer = new InternalAdminTokenIssuer(codec, keys.getPrivate(), "active-key",
            "admin-service", "property-data-admin", Duration.ofSeconds(60));
        RestClient.Builder builder = RestClient.builder().baseUrl("http://property-data");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var client = new RestClientPropertyAdminClient(builder.build(), issuer);
        var principal = new AdminPrincipal(UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "operator", "운영자", Set.of("OPERATOR"), Set.of("COORDINATE_READ"));

        server.expect(once(), requestTo("http://property-data/internal/v1/admin/coordinates/pending?limit=50&offset=0"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-Request-Id", "request-1"))
            .andExpect(request -> {
                String authorization = request.getHeaders().getFirst("Authorization");
                assertThat(authorization).startsWith("Bearer ");
                var verified = codec.verify(authorization.substring(7), new JwtVerificationPolicy(
                    "admin-service", "property-data-admin", Duration.ofSeconds(60), keyId -> keys.getPublic()));
                assertThat(verified.subject()).isEqualTo(principal.accountId().toString());
                assertThat(verified.claims()).containsEntry("requestId", "request-1");
            })
            .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"status\":409,\"detail\":\"conflict\"}"));

        var response = client.exchange(new PropertyAdminClient.Request(HttpMethod.GET,
            "/internal/v1/admin/coordinates/pending", Map.of("limit", "50", "offset", "0"), null,
            principal, "request-1"));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8)).contains("conflict");
        server.verify();
    }
}
