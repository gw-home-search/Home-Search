package com.home.user.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.application.auth.RefreshTokenService;
import com.home.application.auth.port.AccessTokenIssuer;
import com.home.application.favorite.FavoriteService;
import com.home.domain.user.OAuthProvider;
import com.home.domain.user.UserProfile;
import com.home.domain.user.favorite.FavoriteLimitReachedException;
import com.home.domain.user.token.InvalidRefreshTokenException;
import com.home.security.jwt.JwtIssueRequest;
import com.home.security.jwt.Rs256JwtCodec;
import com.home.security.jwt.RsaPemKeys;
import com.home.user.UserServiceApplication;
import com.home.user.oauth.OAuthLoginFacade;
import com.home.user.oauth.OAuthProfile;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(
        classes = UserServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.profiles.active=local")
@AutoConfigureMockMvc
class UserPersistenceConcurrencyTest {
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("home_search_user");
    private static final Path PRIVATE_KEY;
    private static final Path PUBLIC_KEY;
    private static final HttpServer OAUTH_SERVER;

    static {
        try {
            POSTGRES.start();
            try (var connection = java.sql.DriverManager.getConnection(
                            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                    var statement = connection.createStatement()) {
                statement.execute("CREATE ROLE home_search_user_migrator");
                statement.execute("CREATE ROLE home_search_user_runtime");
            }
            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations(System.getProperty("userServiceMigrationLocation"))
                    .schemas("users")
                    .defaultSchema("users")
                    .load()
                    .migrate();
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            PRIVATE_KEY = Files.createTempFile("user-jwt-private", ".pem");
            PUBLIC_KEY = Files.createTempFile("user-jwt-public", ".pem");
            Files.writeString(PRIVATE_KEY, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
            Files.writeString(PUBLIC_KEY, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
            PRIVATE_KEY.toFile().deleteOnExit();
            PUBLIC_KEY.toFile().deleteOnExit();
            OAUTH_SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            OAUTH_SERVER.createContext(
                    "/token",
                    exchange -> respond(
                            exchange,
                            "{\"access_token\":\"provider-access\",\"token_type\":\"Bearer\",\"expires_in\":300}"));
            OAUTH_SERVER.createContext(
                    "/userinfo",
                    exchange -> respond(
                            exchange, "{\"id\":98765,\"kakao_account\":{\"profile\":{\"nickname\":\"OAuth 사용자\"}}}"));
            OAUTH_SERVER.start();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("GOOGLE_OAUTH_CLIENT_ID", () -> "google-test");
        registry.add("GOOGLE_OAUTH_CLIENT_SECRET", () -> "google-secret");
        registry.add("KAKAO_OAUTH_CLIENT_ID", () -> "kakao-test");
        registry.add("KAKAO_OAUTH_CLIENT_SECRET", () -> "kakao-secret");
        registry.add("NAVER_OAUTH_CLIENT_ID", () -> "naver-test");
        registry.add("NAVER_OAUTH_CLIENT_SECRET", () -> "naver-secret");
        registry.add("USER_ALLOWED_ORIGIN", () -> "http://localhost:5173");
        registry.add("USER_OAUTH_SUCCESS_REDIRECT", () -> "http://localhost:5173/auth/success");
        registry.add("USER_OAUTH_FAILURE_REDIRECT", () -> "http://localhost:5173/auth/failure");
        registry.add("home.jwt.active-kid", () -> "test-key");
        registry.add("home.jwt.private-key-path", PRIVATE_KEY::toString);
        registry.add("home.jwt.active-public-key-path", PUBLIC_KEY::toString);
        registry.add("home.cookie.secure", () -> "false");
        registry.add("spring.security.oauth2.client.provider.kakao.token-uri", () -> oauthBaseUrl() + "/token");
        registry.add("spring.security.oauth2.client.provider.kakao.user-info-uri", () -> oauthBaseUrl() + "/userinfo");
    }

    @Autowired
    OAuthLoginFacade login;

    @Autowired
    RefreshTokenService refresh;

    @Autowired
    AccessTokenIssuer accessTokens;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    FavoriteService favorites;

    @Test
    void serializesFirstLoginAndAllowsExactlyOneRefreshRotation() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Long> loginTask = () -> login.login(
                            OAuthProvider.KAKAO,
                            new OAuthProfile("concurrent-subject", new UserProfile("동시 사용자", null, null)))
                    .userId();
            var firstLogin = executor.submit(loginTask);
            var secondLogin = executor.submit(loginTask);
            long firstId = firstLogin.get();
            long secondId = secondLogin.get();
            assertThat(secondId).isEqualTo(firstId);
            assertThat(jdbc.sql(
                                    "SELECT count(*) FROM users.oauth_identity WHERE provider='KAKAO' AND provider_subject='concurrent-subject'")
                            .query(Long.class)
                            .single())
                    .isEqualTo(1L);

            String raw = refresh.issue(firstId).rawToken();
            Callable<Boolean> rotateTask = () -> {
                try {
                    refresh.rotate(raw);
                    return true;
                } catch (InvalidRefreshTokenException ignored) {
                    return false;
                }
            };
            var firstRotate = executor.submit(rotateTask);
            var secondRotate = executor.submit(rotateTask);
            assertThat(java.util.List.of(firstRotate.get(), secondRotate.get())).containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void replacesAndRevokesTheSingleActiveRefreshToken() {
        var user = login.login(
                OAuthProvider.GOOGLE, new OAuthProfile("refresh-replace-user", new UserProfile("교체 사용자", null, null)));
        String replaced = refresh.issue(user.userId()).rawToken();
        String active = refresh.issue(user.userId()).rawToken();

        assertThatThrownBy(() -> refresh.rotate(replaced)).isInstanceOf(InvalidRefreshTokenException.class);
        refresh.revoke(active);
        assertThatThrownBy(() -> refresh.rotate(active)).isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void returnsNotFoundProblemForUnsupportedOAuthProviderThroughSecurityChain() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OAUTH_PROVIDER_NOT_SUPPORTED"));
    }

    @Test
    void enforcesUserJwtAndDenyAllThroughSecurityChain() throws Exception {
        var user = login.login(
                OAuthProvider.GOOGLE,
                new OAuthProfile("security-chain-user", new UserProfile("보안 사용자", "hidden@example.com", null)));
        String accessToken = accessTokens.issue(user.userId());

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json;charset=UTF-8"))
                .andExpect(content().encoding(StandardCharsets.UTF_8))
                .andExpect(jsonPath("$.title").value("인증이 필요합니다"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.userId()))
                .andExpect(jsonPath("$.provider").value("GOOGLE"))
                .andExpect(jsonPath("$.displayName").value("보안 사용자"))
                .andExpect(jsonPath("$.email").doesNotExist());

        for (String rejected : java.util.List.of(
                signedAccessToken(
                        "user-service", "wrong-audience", user.userId(), "USER", Instant.now(), Duration.ofMinutes(15)),
                signedAccessToken(
                        "admin-service",
                        "home-search-user-api",
                        user.userId(),
                        "ADMIN",
                        Instant.now(),
                        Duration.ofMinutes(15)),
                signedAccessToken(
                        "user-service",
                        "home-search-user-api",
                        user.userId(),
                        "USER",
                        Instant.now().minus(Duration.ofMinutes(20)),
                        Duration.ofMinutes(15)))) {
            mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + rejected))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }

        mockMvc.perform(get("/not-allowed").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void rotatesRefreshOnlyForAllowedOriginThroughSecurityChain() throws Exception {
        var user = login.login(
                OAuthProvider.NAVER, new OAuthProfile("refresh-chain-user", new UserProfile("갱신 사용자", null, null)));
        String raw = refresh.issue(user.userId()).rawToken();

        mockMvc.perform(post("/auth/access")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", raw)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(header().string(
                                HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("refresh_token=")));
        mockMvc.perform(post("/auth/access")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "invalid")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void favoriteApiUsesJwtUserOnlyAndKeepsCorsOnAuthenticationFailure() throws Exception {
        var first = login.login(
                OAuthProvider.GOOGLE, new OAuthProfile("favorite-user-a", new UserProfile("관심 사용자 A", null, null)));
        var second = login.login(
                OAuthProvider.GOOGLE, new OAuthProfile("favorite-user-b", new UserProfile("관심 사용자 B", null, null)));
        String firstToken = accessTokens.issue(first.userId());
        String secondToken = accessTokens.issue(second.userId());

        mockMvc.perform(put("/api/v1/favorites/501").header(HttpHeaders.AUTHORIZATION, "Bearer " + firstToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/favorites/501").header(HttpHeaders.AUTHORIZATION, "Bearer " + firstToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorite").value(true));
        mockMvc.perform(get("/api/v1/favorites/501").header(HttpHeaders.AUTHORIZATION, "Bearer " + secondToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorite").value(false));
        mockMvc.perform(get("/api/v1/favorites").header(HttpHeaders.AUTHORIZATION, "Bearer " + firstToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].complexId").value(501))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(delete("/api/v1/favorites/501").header(HttpHeaders.AUTHORIZATION, "Bearer " + secondToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/favorites/501").header(HttpHeaders.AUTHORIZATION, "Bearer " + firstToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorite").value(true));
        mockMvc.perform(delete("/api/v1/favorites/501").header(HttpHeaders.AUTHORIZATION, "Bearer " + firstToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/favorites/0").header(HttpHeaders.AUTHORIZATION, "Bearer " + firstToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COMPLEX_ID"));
        mockMvc.perform(get("/api/v1/favorites")
                        .param("size", "101")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + firstToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
        mockMvc.perform(get("/api/v1/favorites").header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
        mockMvc.perform(options("/api/v1/favorites/501")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                                HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, org.hamcrest.Matchers.containsString("PUT")));
    }

    @Test
    void serializesConcurrentFavoriteAtTheTwoHundredItemLimit() throws Exception {
        var user = login.login(
                OAuthProvider.NAVER, new OAuthProfile("favorite-limit-user", new UserProfile("한도 사용자", null, null)));
        jdbc.sql(
                        "INSERT INTO users.favorite_complex(user_id,complex_id,saved_at) SELECT :userId, value, now() FROM generate_series(1,199) value")
                .param("userId", user.userId())
                .update();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> first = () -> saveFavoriteResult(user.userId(), 200);
            Callable<Boolean> second = () -> saveFavoriteResult(user.userId(), 201);
            var firstResult = executor.submit(first);
            var secondResult = executor.submit(second);
            assertThat(java.util.List.of(firstResult.get(), secondResult.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM users.favorite_complex WHERE user_id=:userId")
                        .param("userId", user.userId())
                        .query(Long.class)
                        .single())
                .isEqualTo(200L);
    }

    @Test
    void validatesOAuthStateAndDeletesSessionAfterCallback() throws Exception {
        OAuthFlow mismatch = authorizeKakao();
        mockMvc.perform(get("/login/oauth2/code/kakao")
                        .param("code", "stub-code")
                        .param("state", "wrong-state")
                        .cookie(mismatch.cookie()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:5173/auth/failure"));

        OAuthFlow expired = authorizeKakao();
        jdbc.sql("DELETE FROM users.oauth_session").update();
        mockMvc.perform(get("/login/oauth2/code/kakao")
                        .param("code", "stub-code")
                        .param("state", expired.state())
                        .cookie(expired.cookie()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:5173/auth/failure"));

        OAuthFlow success = authorizeKakao();
        mockMvc.perform(get("/login/oauth2/code/kakao")
                        .param("code", "stub-code")
                        .param("state", success.state())
                        .cookie(success.cookie()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:5173/auth/success"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("refresh_token=")));
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM users.oauth_session_attributes a JOIN users.oauth_session s ON s.primary_id=a.session_primary_id WHERE s.session_id=:sessionId")
                        .param("sessionId", decodedSessionId(success.cookie()))
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM users.oauth_identity WHERE provider='KAKAO' AND provider_subject='98765'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    private OAuthFlow authorizeKakao() throws Exception {
        var response = mockMvc.perform(get("/oauth2/authorization/kakao"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse();
        var cookie = response.getCookie("OAUTH_SESSION");
        assertThat(cookie).isNotNull();
        String state = java.util.Arrays.stream(
                        URI.create(response.getRedirectedUrl()).getRawQuery().split("&"))
                .map(value -> value.split("=", 2))
                .filter(parts -> parts[0].equals("state"))
                .map(parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8))
                .findFirst()
                .orElseThrow();
        return new OAuthFlow(cookie, state);
    }

    private boolean saveFavoriteResult(long userId, long complexId) {
        try {
            favorites.save(userId, complexId);
            return true;
        } catch (FavoriteLimitReachedException ignored) {
            return false;
        }
    }

    private static String oauthBaseUrl() {
        return "http://127.0.0.1:" + OAUTH_SERVER.getAddress().getPort();
    }

    private static String decodedSessionId(jakarta.servlet.http.Cookie cookie) {
        return new String(Base64.getUrlDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String signedAccessToken(
            String issuer, String audience, long userId, String role, Instant issuedAt, Duration lifetime)
            throws Exception {
        return new Rs256JwtCodec(Clock.fixed(issuedAt, ZoneOffset.UTC))
                .issue(
                        new JwtIssueRequest(
                                issuer,
                                audience,
                                Long.toString(userId),
                                UUID.randomUUID().toString(),
                                "test-key",
                                lifetime,
                                Map.of("role", role)),
                        RsaPemKeys.privateKey(Files.readString(PRIVATE_KEY)));
    }

    @AfterAll
    static void stopResources() {
        OAUTH_SERVER.stop(0);
        POSTGRES.stop();
    }

    private record OAuthFlow(jakarta.servlet.http.Cookie cookie, String state) {}

    private static String pem(String label, byte[] value) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(value) + "\n-----END " + label
                + "-----\n";
    }
}
