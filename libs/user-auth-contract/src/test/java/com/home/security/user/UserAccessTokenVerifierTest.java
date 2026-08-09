package com.home.security.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.security.jwt.JwtIssueRequest;
import com.home.security.jwt.Rs256JwtCodec;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UserAccessTokenVerifierTest {
    private static final Instant NOW = Instant.parse("2026-07-13T06:00:00Z");
    private static KeyPair active;
    private static KeyPair old;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        active = generator.generateKeyPair();
        old = generator.generateKeyPair();
    }

    @Test
    void verifiesCanonicalUserAndOldRotationKey() {
        UserAccessTokenVerifier verifier = verifier(Map.of("active", active.getPublic(), "old", old.getPublic()));

        VerifiedUser user = verifier.verifyBearer(
                "Bearer " + token(old, "old", "42", Duration.ofMinutes(15), Map.of("role", "USER")));

        assertThat(user).isEqualTo(new VerifiedUser(42, "token-id", NOW, NOW.plus(Duration.ofMinutes(15))));
    }

    @Test
    void rejectsMissingBearerTamperingWrongAlgorithmAndUnknownKid() {
        UserAccessTokenVerifier verifier = verifier(Map.of("active", active.getPublic()));
        String valid = token(active, "active", "42", Duration.ofMinutes(15), Map.of("role", "USER"));
        String hs256 = Jwts.builder()
                .header()
                .keyId("active")
                .and()
                .issuer("user-service")
                .audience()
                .add("home-search-user-api")
                .and()
                .subject("42")
                .id("token-id")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(60)))
                .claim("role", "USER")
                .signWith(Jwts.SIG.HS256.key().build())
                .compact();

        assertRejected(() -> verifier.verifyBearer(null));
        assertRejected(() -> verifier.verifyBearer(valid));
        String tampered = tamperSignature(valid);
        assertThat(tampered).isNotEqualTo(valid);
        assertRejected(() -> verifier.verifyBearer("Bearer " + tampered));
        assertRejected(() -> verifier.verifyBearer("Bearer " + hs256));
        assertRejected(() -> verifier.verifyBearer(
                "Bearer " + token(active, "unknown", "42", Duration.ofMinutes(15), Map.of("role", "USER"))));
    }

    private static String tamperSignature(String token) {
        int signatureStart = token.lastIndexOf('.') + 1;
        char original = token.charAt(signatureStart);
        char replacement = original == 'A' ? 'B' : 'A';
        return token.substring(0, signatureStart) + replacement + token.substring(signatureStart + 1);
    }

    @Test
    void rejectsIssuerAudienceTimeJtiAndRoleViolations() {
        UserAccessTokenVerifier verifier = verifier(Map.of("active", active.getPublic()));
        assertRejected(() -> verifier.verifyBearer("Bearer "
                + raw(
                        "other",
                        new String[] {"home-search-user-api"},
                        "42",
                        "token-id",
                        NOW,
                        NOW.plusSeconds(60),
                        Map.of("role", "USER"))));
        assertRejected(() -> verifier.verifyBearer("Bearer "
                + raw(
                        "user-service",
                        new String[] {"other"},
                        "42",
                        "token-id",
                        NOW,
                        NOW.plusSeconds(60),
                        Map.of("role", "USER"))));
        assertRejected(() -> verifier.verifyBearer("Bearer "
                + raw(
                        "user-service",
                        new String[] {"home-search-user-api", "other"},
                        "42",
                        "token-id",
                        NOW,
                        NOW.plusSeconds(60),
                        Map.of("role", "USER"))));
        assertRejected(() -> verifier.verifyBearer("Bearer "
                + token(active, "active", "42", Duration.ofMinutes(15).plusSeconds(1), Map.of("role", "USER"))));
        assertRejected(() -> verifier.verifyBearer("Bearer "
                + raw(
                        "user-service",
                        new String[] {"home-search-user-api"},
                        "42",
                        "token-id",
                        NOW.plusSeconds(1),
                        NOW.plusSeconds(61),
                        Map.of("role", "USER"))));
        assertRejected(() -> verifier.verifyBearer("Bearer "
                + raw(
                        "user-service",
                        new String[] {"home-search-user-api"},
                        "42",
                        null,
                        NOW,
                        NOW.plusSeconds(60),
                        Map.of("role", "USER"))));
        assertRejected(() -> verifier.verifyBearer(
                "Bearer " + token(active, "active", "42", Duration.ofMinutes(15), Map.of("role", "ADMIN"))));
    }

    @Test
    void rejectsInvalidSubjectsAndInvalidPolicyKeys() throws Exception {
        UserAccessTokenVerifier verifier = verifier(Map.of("active", active.getPublic()));
        for (String subject : new String[] {"0", "-1", "not-number", "9223372036854775808"}) {
            assertRejected(() -> verifier.verifyBearer(
                    "Bearer " + token(active, "active", subject, Duration.ofMinutes(15), Map.of("role", "USER"))));
        }
        KeyPairGenerator weakGenerator = KeyPairGenerator.getInstance("RSA");
        weakGenerator.initialize(1024);
        KeyPair weak = weakGenerator.generateKeyPair();
        assertThatThrownBy(() -> new UserAccessTokenPolicy(
                        "user-service", "home-search-user-api", Duration.ofMinutes(15), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserAccessTokenPolicy(
                        "user-service",
                        "home-search-user-api",
                        Duration.ofMinutes(15),
                        Map.of("weak", weak.getPublic())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static UserAccessTokenVerifier verifier(Map<String, java.security.PublicKey> keys) {
        return new UserAccessTokenVerifier(
                new UserAccessTokenPolicy("user-service", "home-search-user-api", Duration.ofMinutes(15), keys),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String token(
            KeyPair key, String kid, String subject, Duration lifetime, Map<String, Object> claims) {
        return new Rs256JwtCodec(Clock.fixed(NOW, ZoneOffset.UTC))
                .issue(
                        new JwtIssueRequest(
                                "user-service", "home-search-user-api", subject, "token-id", kid, lifetime, claims),
                        key.getPrivate());
    }

    private static String raw(
            String issuer,
            String[] audiences,
            String subject,
            String tokenId,
            Instant issuedAt,
            Instant expiresAt,
            Map<String, Object> claims) {
        var builder = Jwts.builder().header().keyId("active").and().issuer(issuer);
        var audience = builder.audience();
        for (String value : audiences) audience.add(value);
        builder = audience.and()
                .subject(subject)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .claims(claims);
        if (tokenId != null) builder.id(tokenId);
        return builder.signWith(active.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable).isExactlyInstanceOf(UserJwtVerificationException.class);
    }
}
