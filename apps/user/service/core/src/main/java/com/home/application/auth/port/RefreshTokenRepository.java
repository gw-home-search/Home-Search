package com.home.application.auth.port;

import com.home.domain.user.token.ActiveRefreshToken;
import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository {
    void replaceActive(ActiveRefreshToken token);

    Optional<ActiveRefreshToken> findActiveByHash(String hash);

    boolean rotateActive(String expectedHash, ActiveRefreshToken replacement, Instant now);

    void revokeByHash(String hash, Instant now);
}
