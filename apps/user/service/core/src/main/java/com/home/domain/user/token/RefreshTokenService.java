package com.home.domain.user.token;

import java.time.Duration;
import java.util.Objects;

public final class RefreshTokenService {
    private final RefreshTokenRepository repository;
    private final OpaqueTokenGenerator generator;
    private final TokenClock clock;
    private final Duration timeToLive;

    public RefreshTokenService(
            RefreshTokenRepository repository,
            OpaqueTokenGenerator generator,
            TokenClock clock,
            Duration timeToLive
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.generator = Objects.requireNonNull(generator);
        this.clock = Objects.requireNonNull(clock);
        this.timeToLive = Objects.requireNonNull(timeToLive);
        if (timeToLive.isZero() || timeToLive.isNegative()) {
            throw new IllegalArgumentException("Refresh token TTL must be positive");
        }
    }

    public String issue(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        String rawToken = generator.generate();
        repository.replaceActive(new StoredRefreshToken(
                userId,
                RefreshTokenHash.sha256(rawToken),
                clock.now().plus(timeToLive)
        ));
        return rawToken;
    }

    public String rotate(String rawToken) {
        String expectedHash = RefreshTokenHash.sha256(rawToken);
        StoredRefreshToken active = repository.findActiveByHash(expectedHash)
                .filter(token -> !token.isExpiredAt(clock.now()))
                .orElseThrow(InvalidRefreshTokenException::new);
        String replacementRawToken = generator.generate();
        StoredRefreshToken replacement = new StoredRefreshToken(
                active.userId(),
                RefreshTokenHash.sha256(replacementRawToken),
                clock.now().plus(timeToLive)
        );
        if (!repository.rotateActive(expectedHash, replacement, clock.now())) {
            throw new InvalidRefreshTokenException();
        }
        return replacementRawToken;
    }

    public void revoke(String rawToken) {
        repository.findActiveByHash(RefreshTokenHash.sha256(rawToken))
                .ifPresent(token -> repository.revokeByUserId(token.userId(), clock.now()));
    }
}
