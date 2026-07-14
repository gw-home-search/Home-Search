package com.home.application.auth;

import com.home.application.auth.port.OpaqueTokenGenerator;
import com.home.application.auth.port.RefreshTokenRepository;
import com.home.application.auth.port.TokenClock;
import com.home.domain.user.token.ActiveRefreshToken;
import com.home.domain.user.token.InvalidRefreshTokenException;
import com.home.domain.user.token.RefreshTokenHash;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {
    private final RefreshTokenRepository repository;
    private final OpaqueTokenGenerator generator;
    private final TokenClock clock;
    private final RefreshTokenSettings settings;

    public RefreshTokenService(
            RefreshTokenRepository repository,
            OpaqueTokenGenerator generator,
            TokenClock clock,
            RefreshTokenSettings settings) {
        this.repository = Objects.requireNonNull(repository);
        this.generator = Objects.requireNonNull(generator);
        this.clock = Objects.requireNonNull(clock);
        this.settings = Objects.requireNonNull(settings);
    }

    @Transactional
    public IssuedRefreshToken issue(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        Instant now = clock.now();
        String raw = generator.generate();
        Instant expiresAt = now.plus(settings.ttl());
        repository.replaceActive(new ActiveRefreshToken(userId, RefreshTokenHash.sha256(raw), now, expiresAt));
        return new IssuedRefreshToken(userId, raw, expiresAt);
    }

    @Transactional
    public RotatedRefreshToken rotate(String rawToken) {
        Instant now = clock.now();
        String expectedHash = RefreshTokenHash.sha256(rawToken);
        ActiveRefreshToken active = repository
                .findActiveByHash(expectedHash)
                .filter(token -> !token.isExpiredAt(now))
                .orElseThrow(InvalidRefreshTokenException::new);
        String replacementRaw = generator.generate();
        Instant expiresAt = now.plus(settings.ttl());
        var replacement =
                new ActiveRefreshToken(active.userId(), RefreshTokenHash.sha256(replacementRaw), now, expiresAt);
        if (!repository.rotateActive(expectedHash, replacement, now)) throw new InvalidRefreshTokenException();
        return new RotatedRefreshToken(active.userId(), replacementRaw, expiresAt);
    }

    @Transactional
    public void revoke(String rawToken) {
        try {
            repository.revokeByHash(RefreshTokenHash.sha256(rawToken), clock.now());
        } catch (InvalidRefreshTokenException ignored) {
        }
    }
}
