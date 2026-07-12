package com.home.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import com.home.admin.security.AdminPrincipal;
import com.home.security.jwt.JwtVerificationPolicy;
import com.home.security.jwt.Rs256JwtCodec;

import org.junit.jupiter.api.Test;

class InternalAdminTokenIssuerTest {
    @Test
    void issuesSixtySecondActorAndPermissionClaimsForPropertyData() throws Exception {
        Instant now = Instant.parse("2026-07-12T00:00:00Z");
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();
        var codec = new Rs256JwtCodec(Clock.fixed(now, ZoneOffset.UTC));
        var issuer = new InternalAdminTokenIssuer(codec, keys.getPrivate(), "active-key",
            "admin-service", "property-data-admin", Duration.ofSeconds(60));
        var principal = new AdminPrincipal(UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "operator", "운영자", Set.of("OPERATOR"), Set.of("COORDINATE_READ", "COORDINATE_WRITE"));

        String token = issuer.issue(principal, "request-1");
        var verified = codec.verify(token, new JwtVerificationPolicy(
            "admin-service", "property-data-admin", Duration.ofSeconds(60), keyId -> keys.getPublic()));

        assertThat(verified.keyId()).isEqualTo("active-key");
        assertThat(verified.subject()).isEqualTo(principal.accountId().toString());
        assertThat(verified.tokenId()).isNotBlank().isNotEqualTo("request-1");
        assertThat(verified.claims()).containsEntry("loginId", "operator").containsEntry("requestId", "request-1");
        assertThat(verified.claims().get("permissions")).asList().contains("COORDINATE_WRITE");
        assertThat(verified.expiresAt()).isEqualTo(now.plusSeconds(60));
    }

    @Test
    void rejectsBlankRequestIdsAndLifetimeOverSixtySeconds() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var keys = generator.generateKeyPair();
        var codec = new Rs256JwtCodec(Clock.systemUTC());
        var principal = new AdminPrincipal(UUID.randomUUID(), "operator", "운영자",
            Set.of("OPERATOR"), Set.of("COORDINATE_READ"));
        var issuer = new InternalAdminTokenIssuer(codec, keys.getPrivate(), "active-key",
            "admin-service", "property-data-admin", Duration.ofSeconds(60));

        assertThatThrownBy(() -> issuer.issue(principal, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalAdminTokenIssuer(codec, keys.getPrivate(), "active-key",
            "admin-service", "property-data-admin", Duration.ofSeconds(61)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
