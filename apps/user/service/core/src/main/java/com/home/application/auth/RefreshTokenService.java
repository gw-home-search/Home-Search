package com.home.application.auth;

import com.home.application.auth.port.OpaqueTokenGenerator;
import com.home.application.auth.port.RefreshTokenRepository;
import com.home.application.auth.port.TokenClock;
import com.home.domain.user.token.ActiveRefreshToken;
import com.home.domain.user.token.InvalidRefreshTokenException;
import com.home.domain.user.token.RefreshTokenHash;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class RefreshTokenService {
    private final RefreshTokenRepository repository;
    private final OpaqueTokenGenerator generator;
    private final TokenClock clock;
    private final Duration ttl;

    public RefreshTokenService(RefreshTokenRepository repository, OpaqueTokenGenerator generator, TokenClock clock, Duration ttl) {
        this.repository = Objects.requireNonNull(repository);
        this.generator = Objects.requireNonNull(generator);
        this.clock = Objects.requireNonNull(clock);
        this.ttl = Objects.requireNonNull(ttl);
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Refresh token TTL must be positive");
    }

    public IssuedRefreshToken issue(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        Instant now = clock.now();
        String raw = generator.generate();
        Instant expiresAt = now.plus(ttl);
        repository.replaceActive(new ActiveRefreshToken(userId, RefreshTokenHash.sha256(raw), now, expiresAt));
        return new IssuedRefreshToken(userId, raw, expiresAt);
    }

    public RotatedRefreshToken rotate(String rawToken) {
        Instant now = clock.now();
        String expectedHash = RefreshTokenHash.sha256(rawToken);
        ActiveRefreshToken active = repository.findActiveByHash(expectedHash)
                .filter(token -> !token.isExpiredAt(now)).orElseThrow(InvalidRefreshTokenException::new);
        String replacementRaw = generator.generate();
        Instant expiresAt = now.plus(ttl);
        var replacement = new ActiveRefreshToken(active.userId(), RefreshTokenHash.sha256(replacementRaw), now, expiresAt);
        if (!repository.rotateActive(expectedHash, replacement, now)) throw new InvalidRefreshTokenException();
        return new RotatedRefreshToken(active.userId(), replacementRaw, expiresAt);
    }

    public void revoke(String rawToken) {
        try { repository.revokeByHash(RefreshTokenHash.sha256(rawToken), clock.now()); }
        catch (InvalidRefreshTokenException ignored) { }
    }
}
