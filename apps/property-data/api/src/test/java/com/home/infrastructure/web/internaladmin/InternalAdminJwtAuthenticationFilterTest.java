package com.home.infrastructure.web.internaladmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.security.jwt.JwtIssueRequest;
import com.home.security.jwt.JwtVerificationPolicy;
import com.home.security.jwt.Rs256JwtCodec;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class InternalAdminJwtAuthenticationFilterTest {
    private static final Instant NOW = Instant.parse("2026-07-12T00:00:00Z");
    private static final Pattern OFFSET_TIMESTAMP =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:Z|[+-]\\d{2}:\\d{2})$");
    private final UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private Rs256JwtCodec codec;
    private java.security.KeyPair keys;

    @BeforeEach
    void setUp() throws Exception {
        codec = new Rs256JwtCodec(Clock.fixed(NOW, ZoneOffset.UTC));
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
    }

    @Test
    @DisplayName("유효한 internal token은 요청 principal을 생성한다")
    void validInternalTokenCreatesPrincipalForTheRequest() throws Exception {
        var filter = filter("property-data-admin");
        var request = internalRequest(token(Map.of(
                "loginId",
                "operator",
                "requestId",
                "request-1",
                "roles",
                List.of("OPERATOR"),
                "permissions",
                List.of("COORDINATE_READ", "COORDINATE_WRITE"))));
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        InternalAdminPrincipal principal =
                (InternalAdminPrincipal) chain.getRequest().getAttribute(InternalAdminPrincipal.REQUEST_ATTRIBUTE);
        assertThat(principal.accountId()).isEqualTo(accountId);
        assertThat(principal.loginId()).isEqualTo("operator");
        assertThat(principal.requestId()).isEqualTo("request-1");
        assertThat(principal.permissions()).contains("COORDINATE_WRITE");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("audience 오류와 requestId claim 누락은 chain 호출 없이 거부한다")
    void wrongAudienceAndMissingRequestIdAreRejectedWithoutCallingTheChain() throws Exception {
        var wrongAudience = filter("wrong-audience");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        wrongAudience.doFilter(internalRequest(token(validClaims())), response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();

        var missingClaimResponse = new MockHttpServletResponse();
        var missingClaimChain = new MockFilterChain();
        filter("property-data-admin")
                .doFilter(
                        internalRequest(token(Map.of(
                                "loginId",
                                "operator",
                                "roles",
                                List.of("OPERATOR"),
                                "permissions",
                                List.of("COORDINATE_READ")))),
                        missingClaimResponse,
                        missingClaimChain);
        assertThat(missingClaimResponse.getStatus()).isEqualTo(401);
        assertThat(missingClaimChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("Bearer header 누락과 잘못된 authorization claim을 거부한다")
    void missingBearerHeaderAndMalformedAuthorizationClaimsAreRejected() throws Exception {
        var missingHeader = new MockHttpServletRequest("GET", "/internal/v1/admin/metadata/pending");
        var missingHeaderResponse = new MockHttpServletResponse();
        filter("property-data-admin").doFilter(missingHeader, missingHeaderResponse, new MockFilterChain());
        assertThat(missingHeaderResponse.getStatus()).isEqualTo(401);
        assertThat(missingHeaderResponse.getContentType()).isEqualTo("application/problem+json");
        var problem = new ObjectMapper().readTree(missingHeaderResponse.getContentAsByteArray());
        assertThat(problem.get("type").asText()).isEqualTo("about:blank");
        assertThat(problem.get("title").asText()).isEqualTo("Unauthorized");
        assertThat(problem.get("detail").asText()).isEqualTo("Internal admin authentication failed.");
        assertThat(problem.get("exception").asText()).isEqualTo("InternalAdminAuthenticationException");
        assertThat(problem.get("timestamp").asText()).matches(OFFSET_TIMESTAMP);

        var malformedClaims = new java.util.LinkedHashMap<String, Object>(validClaims());
        malformedClaims.put("roles", "OPERATOR");
        var malformedResponse = new MockHttpServletResponse();
        filter("property-data-admin")
                .doFilter(internalRequest(token(malformedClaims)), malformedResponse, new MockFilterChain());
        assertThat(malformedResponse.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("requestId header 누락과 token 불일치를 거부한다")
    void missingOrMismatchedRequestIdHeaderIsRejected() throws Exception {
        var missing = internalRequest(token(validClaims()));
        missing.removeHeader("X-Request-Id");
        var missingResponse = new MockHttpServletResponse();
        filter("property-data-admin").doFilter(missing, missingResponse, new MockFilterChain());
        assertThat(missingResponse.getStatus()).isEqualTo(401);

        var mismatched = internalRequest(token(validClaims()));
        mismatched.removeHeader("X-Request-Id");
        mismatched.addHeader("X-Request-Id", "forged-request");
        var mismatchedResponse = new MockHttpServletResponse();
        filter("property-data-admin").doFilter(mismatched, mismatchedResponse, new MockFilterChain());
        assertThat(mismatchedResponse.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("public API는 internal authentication을 요구하지 않는다")
    void publicApiDoesNotRequireInternalAuthentication() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/map/complexes");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter("property-data-admin").doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("인증 이후 downstream 예외는 authentication failure로 재분류하지 않는다")
    void authenticatedDownstreamExceptionsAreNotReclassifiedAsAuthenticationFailures() {
        var request = internalRequest(token(validClaims()));
        var response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter("property-data-admin")
                        .doFilter(request, response, (servletRequest, servletResponse) -> {
                            throw new IllegalArgumentException("domain validation");
                        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("domain validation");
    }

    private InternalAdminJwtAuthenticationFilter filter(String expectedAudience) {
        return new InternalAdminJwtAuthenticationFilter(
                codec,
                new JwtVerificationPolicy(
                        "admin-service",
                        expectedAudience,
                        Duration.ofSeconds(60),
                        keyId -> "test-key".equals(keyId) ? keys.getPublic() : null),
                new ObjectMapper());
    }

    private MockHttpServletRequest internalRequest(String token) {
        var request = new MockHttpServletRequest("GET", "/internal/v1/admin/coordinates/pending");
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader("X-Request-Id", "request-1");
        return request;
    }

    private String token(Map<String, Object> claims) {
        return codec.issue(
                new JwtIssueRequest(
                        "admin-service",
                        "property-data-admin",
                        accountId.toString(),
                        "token-id",
                        "test-key",
                        Duration.ofSeconds(60),
                        claims),
                keys.getPrivate());
    }

    private Map<String, Object> validClaims() {
        return Map.of(
                "loginId",
                "operator",
                "requestId",
                "request-1",
                "roles",
                List.of("OPERATOR"),
                "permissions",
                List.of("COORDINATE_READ"));
    }
}
