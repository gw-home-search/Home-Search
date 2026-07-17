package com.home.chatbff.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.security.jwt.JwtIssueRequest;
import com.home.security.jwt.Rs256JwtCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfiguredChatUserAuthenticatorTest {
    private static KeyPair keyPair;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @Test
    @DisplayName("설정된 공개키로 canonical USER JWT를 검증한다")
    void authenticatesCanonicalUserJwt(@TempDir Path tempDir) throws Exception {
        Path publicKey = writePublicKey(tempDir);
        var authenticator = new ConfiguredChatUserAuthenticator(properties("active=" + publicKey));
        String token = new Rs256JwtCodec(Clock.systemUTC())
                .issue(
                        new JwtIssueRequest(
                                "user-service",
                                "home-search-user-api",
                                "42",
                                "token-id",
                                "active",
                                Duration.ofMinutes(15),
                                Map.of("role", "USER")),
                        keyPair.getPrivate());

        VerifiedChatUser user = authenticator.authenticate("Bearer " + token);

        assertThat(user.userId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("공개키가 없거나 JWT가 유효하지 않으면 fail-closed로 거부한다")
    void rejectsMissingKeysAndInvalidBearer(@TempDir Path tempDir) throws Exception {
        var withoutKeys = new ConfiguredChatUserAuthenticator(properties(""));
        var withKey = new ConfiguredChatUserAuthenticator(properties("active=" + writePublicKey(tempDir)));

        assertThatThrownBy(() -> withoutKeys.authenticate("Bearer token"))
                .isExactlyInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> withKey.authenticate("Bearer invalid"))
                .isExactlyInstanceOf(AuthenticationRequiredException.class);
    }

    @Test
    @DisplayName("공개키 경로 설정 오류는 서비스 시작 시 명시적으로 차단한다")
    void rejectsMalformedKeyPathConfiguration(@TempDir Path tempDir) throws Exception {
        Path publicKey = writePublicKey(tempDir);
        Path oversized = tempDir.resolve("oversized.pem");
        Files.writeString(oversized, "x".repeat(16 * 1024 + 1));

        for (String encoded : new String[] {
            "missing-separator",
            "=path",
            "active=",
            "active=" + tempDir.resolve("missing.pem"),
            "active=" + oversized,
            "active=" + publicKey + ",active=" + publicKey
        }) {
            assertThatThrownBy(() -> new ConfiguredChatUserAuthenticator(properties(encoded)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("invalid chat BFF JWT public key configuration");
        }
    }

    @Test
    @DisplayName("JWT와 AI 설정은 canonical 필수값과 양수 timeout을 강제한다")
    void validatesConfigurationRecords() {
        assertThatThrownBy(() -> new UserJwtProperties(null, "aud", Duration.ofMinutes(15), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserJwtProperties("issuer", " ", Duration.ofMinutes(15), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserJwtProperties("issuer", "aud", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserJwtProperties("issuer", "aud", Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new UserJwtProperties("issuer", "aud", Duration.ofMinutes(1), null).publicKeyPaths())
                .isEmpty();
    }

    private static UserJwtProperties properties(String paths) {
        return new UserJwtProperties("user-service", "home-search-user-api", Duration.ofMinutes(15), paths);
    }

    private static Path writePublicKey(Path directory) throws Exception {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(keyPair.getPublic().getEncoded());
        Path path = directory.resolve("user-public.pem");
        Files.writeString(path, "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----\n");
        return path;
    }
}
