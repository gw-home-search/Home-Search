package com.home.admin.internal;

import com.home.admin.security.AdminPrincipal;
import com.home.security.jwt.JwtIssueRequest;
import com.home.security.jwt.Rs256JwtCodec;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

public final class InternalAdminTokenIssuer {
    private static final Duration MAXIMUM_LIFETIME = Duration.ofSeconds(60);
    private final Rs256JwtCodec codec;
    private final PrivateKey privateKey;
    private final String keyId;
    private final String issuer;
    private final String audience;
    private final Duration lifetime;

    public InternalAdminTokenIssuer(
            Rs256JwtCodec codec,
            PrivateKey privateKey,
            String keyId,
            String issuer,
            String audience,
            Duration lifetime) {
        if (codec == null
                || privateKey == null
                || blank(keyId)
                || blank(issuer)
                || blank(audience)
                || lifetime == null
                || lifetime.isZero()
                || lifetime.isNegative()
                || lifetime.compareTo(MAXIMUM_LIFETIME) > 0) {
            throw new IllegalArgumentException("invalid internal token issuer configuration");
        }
        this.codec = codec;
        this.privateKey = privateKey;
        this.keyId = keyId;
        this.issuer = issuer;
        this.audience = audience;
        this.lifetime = lifetime;
    }

    public String issue(AdminPrincipal principal, String requestId) {
        if (principal == null
                || principal.accountId() == null
                || blank(principal.loginId())
                || blank(requestId)
                || principal.roles() == null
                || principal.roles().isEmpty()
                || principal.permissions() == null
                || principal.permissions().isEmpty()) {
            throw new IllegalArgumentException("internal token actor and requestId are required");
        }
        return codec.issue(
                new JwtIssueRequest(
                        issuer,
                        audience,
                        principal.accountId().toString(),
                        UUID.randomUUID().toString(),
                        keyId,
                        lifetime,
                        Map.of(
                                "loginId", principal.loginId(),
                                "requestId", requestId,
                                "roles", principal.roles().stream().sorted().toList(),
                                "permissions",
                                        principal.permissions().stream()
                                                .sorted()
                                                .toList())),
                privateKey);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
