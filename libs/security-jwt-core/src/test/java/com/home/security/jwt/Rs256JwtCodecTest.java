package com.home.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import io.jsonwebtoken.Jwts;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Rs256JwtCodecTest {
    private static final Instant NOW = Instant.parse("2026-07-12T00:00:00Z");
    private static KeyPair keyPair;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @Test
    void roundTripsStandardAndCustomClaimsThroughTheSelectedKeyId() {
        Rs256JwtCodec codec = new Rs256JwtCodec(Clock.fixed(NOW, ZoneOffset.UTC));
        String token = codec.issue(new JwtIssueRequest(
            "admin-service", "property-data-admin", "account-id", "request-id", "active-key",
            Duration.ofSeconds(60), Map.of("loginId", "operator", "permissions", java.util.List.of("COORDINATE_WRITE"))
        ), keyPair.getPrivate());

        VerifiedJwt verified = codec.verify(token, new JwtVerificationPolicy(
            "admin-service", "property-data-admin", Duration.ofSeconds(60),
            keyId -> "active-key".equals(keyId) ? keyPair.getPublic() : null
        ));

        assertThat(verified.keyId()).isEqualTo("active-key");
        assertThat(verified.subject()).isEqualTo("account-id");
        assertThat(verified.tokenId()).isEqualTo("request-id");
        assertThat(verified.claims()).containsEntry("loginId", "operator");
        assertThat(verified.expiresAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void rejectsWrongAudienceTamperingUnknownKeysAndExcessiveLifetime() {
        Rs256JwtCodec codec = new Rs256JwtCodec(Clock.fixed(NOW, ZoneOffset.UTC));
        String token = codec.issue(new JwtIssueRequest(
            "admin-service", "property-data-admin", "account-id", "request-id", "active-key",
            Duration.ofSeconds(60), Map.of()
        ), keyPair.getPrivate());
        JwtVerificationPolicy valid = new JwtVerificationPolicy(
            "admin-service", "property-data-admin", Duration.ofSeconds(60), keyId -> keyPair.getPublic());

        assertThatThrownBy(() -> codec.verify(token, new JwtVerificationPolicy(
            "admin-service", "wrong-audience", Duration.ofSeconds(60), keyId -> keyPair.getPublic())))
            .isInstanceOf(JwtVerificationException.class);
        assertThatThrownBy(() -> codec.verify(token.substring(0, token.length() - 2) + "xx", valid))
            .isInstanceOf(JwtVerificationException.class);
        assertThatThrownBy(() -> codec.verify(token, new JwtVerificationPolicy(
            "admin-service", "property-data-admin", Duration.ofSeconds(60), keyId -> null)))
            .isInstanceOf(JwtVerificationException.class);

        String longLived = codec.issue(new JwtIssueRequest(
            "admin-service", "property-data-admin", "account-id", "request-id", "active-key",
            Duration.ofSeconds(61), Map.of()
        ), keyPair.getPrivate());
        assertThatThrownBy(() -> codec.verify(longLived, valid)).isInstanceOf(JwtVerificationException.class);
    }

    @Test
    void rejectsTokensWithAdditionalAudience() {
        String token = Jwts.builder()
            .header().keyId("active-key").and()
            .issuer("admin-service")
            .audience().add("other-service").add("property-data-admin").and()
            .subject("account-id")
            .id("request-id")
            .issuedAt(java.util.Date.from(NOW))
            .expiration(java.util.Date.from(NOW.plusSeconds(60)))
            .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
            .compact();

        assertThatThrownBy(() -> new Rs256JwtCodec(Clock.fixed(NOW, ZoneOffset.UTC)).verify(token,
            new JwtVerificationPolicy("admin-service", "property-data-admin", Duration.ofSeconds(60),
                keyId -> keyPair.getPublic())))
            .isInstanceOf(JwtVerificationException.class);
    }

    @Test
    void rejectsExpiredTokensAndReservedCustomClaims() {
        Rs256JwtCodec issuer = new Rs256JwtCodec(Clock.fixed(NOW, ZoneOffset.UTC));
        String token = issuer.issue(new JwtIssueRequest(
            "admin-service", "property-data-admin", "account-id", "request-id", "old-key",
            Duration.ofSeconds(60), Map.of()
        ), keyPair.getPrivate());
        Rs256JwtCodec verifier = new Rs256JwtCodec(Clock.fixed(NOW.plusSeconds(61), ZoneOffset.UTC));

        assertThatThrownBy(() -> verifier.verify(token, new JwtVerificationPolicy(
            "admin-service", "property-data-admin", Duration.ofSeconds(60),
            keyId -> "old-key".equals(keyId) ? keyPair.getPublic() : null
        ))).isInstanceOf(JwtVerificationException.class);
        assertThatThrownBy(() -> new JwtIssueRequest(
            "admin-service", "property-data-admin", "account-id", "request-id", "active-key",
            Duration.ofSeconds(60), Map.of("sub", "forged-subject")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAnOldKeyDuringRotationOnlyWhenItsKeyIdRemainsConfigured() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair oldKey = generator.generateKeyPair();
        Rs256JwtCodec codec = new Rs256JwtCodec(Clock.fixed(NOW, ZoneOffset.UTC));
        String token = codec.issue(new JwtIssueRequest(
            "admin-service", "property-data-admin", "account-id", "request-id", "old-key",
            Duration.ofSeconds(60), Map.of()
        ), oldKey.getPrivate());

        assertThat(codec.verify(token, new JwtVerificationPolicy(
            "admin-service", "property-data-admin", Duration.ofSeconds(60),
            keyId -> Map.of("old-key", oldKey.getPublic(), "active-key", keyPair.getPublic()).get(keyId)
        )).keyId()).isEqualTo("old-key");
        assertThatThrownBy(() -> codec.verify(token, new JwtVerificationPolicy(
            "admin-service", "property-data-admin", Duration.ofSeconds(60),
            keyId -> Map.of("active-key", keyPair.getPublic()).get(keyId)
        ))).isInstanceOf(JwtVerificationException.class);
    }
}
