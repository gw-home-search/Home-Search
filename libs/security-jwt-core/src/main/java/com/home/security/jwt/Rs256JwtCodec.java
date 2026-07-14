package com.home.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.UnsupportedJwtException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class Rs256JwtCodec {
    private static final Set<String> REGISTERED = Set.of("iss", "aud", "sub", "jti", "iat", "exp", "nbf");
    private final Clock clock;

    public Rs256JwtCodec(Clock clock) {
        if (clock == null) throw new IllegalArgumentException("clock is required");
        this.clock = clock;
    }

    public String issue(JwtIssueRequest request, PrivateKey privateKey) {
        if (request == null || privateKey == null)
            throw new IllegalArgumentException("request and privateKey are required");
        Instant issuedAt = clock.instant();
        return Jwts.builder()
                .header()
                .keyId(request.keyId())
                .and()
                .claims(request.claims())
                .issuer(request.issuer())
                .audience()
                .add(request.audience())
                .and()
                .subject(request.subject())
                .id(request.tokenId())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(request.lifetime())))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    public VerifiedJwt verify(String token, JwtVerificationPolicy policy) {
        if (token == null || token.isBlank() || policy == null) throw new JwtVerificationException();
        try {
            Jws<Claims> parsed = Jwts.parser()
                    .keyLocator(header -> resolveKey(
                            header.get("kid") instanceof String keyId ? keyId : null, header.getAlgorithm(), policy))
                    .requireIssuer(policy.issuer())
                    .requireAudience(policy.audience())
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token);
            Claims claims = parsed.getPayload();
            Date issuedAtValue = claims.getIssuedAt();
            Date expiresAtValue = claims.getExpiration();
            if (issuedAtValue == null || expiresAtValue == null) throw new JwtVerificationException();
            Instant issuedAt = issuedAtValue.toInstant();
            Instant expiresAt = expiresAtValue.toInstant();
            if (issuedAt.isAfter(clock.instant())
                    || !expiresAt.isAfter(issuedAt)
                    || Duration.between(issuedAt, expiresAt).compareTo(policy.maximumLifetime()) > 0) {
                throw new JwtVerificationException();
            }
            Set<String> audiences = claims.getAudience();
            if (audiences.size() != 1 || !audiences.contains(policy.audience())) {
                throw new JwtVerificationException();
            }
            String audience = policy.audience();
            Map<String, Object> customClaims = new LinkedHashMap<>(claims);
            REGISTERED.forEach(customClaims::remove);
            return new VerifiedJwt(
                    parsed.getHeader().getKeyId(),
                    claims.getIssuer(),
                    audience,
                    required(claims.getSubject()),
                    required(claims.getId()),
                    issuedAt,
                    expiresAt,
                    customClaims);
        } catch (JwtVerificationException exception) {
            throw exception;
        } catch (JwtException | IllegalArgumentException exception) {
            throw new JwtVerificationException();
        }
    }

    private PublicKey resolveKey(String keyId, String algorithm, JwtVerificationPolicy policy) {
        if (!"RS256".equals(algorithm) || keyId == null || keyId.isBlank())
            throw new UnsupportedJwtException("unsupported token header");
        PublicKey key = policy.keyResolver().apply(keyId);
        if (key == null) throw new UnsupportedJwtException("unknown signing key");
        return key;
    }

    private String required(String value) {
        if (value == null || value.isBlank()) throw new JwtVerificationException();
        return value;
    }
}
