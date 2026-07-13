package com.home.domain.user.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RefreshTokenServiceTest {

    @Test
    void rotatesTheOnlyActiveTokenAndRejectsReuse() {
        var repository = new InMemoryRefreshTokenRepository();
        TokenClock clock = () -> Instant.parse("2026-07-13T00:00:00Z");
        var service = new RefreshTokenService(repository, new SequentialOpaqueTokenGenerator(), clock, Duration.ofDays(30));

        String first = service.issue(42L);
        String second = service.rotate(first);

        assertThat(second).isNotEqualTo(first);
        assertThat(repository.findActiveByUserId(42L)).get().extracting(StoredRefreshToken::tokenHash)
                .isEqualTo(RefreshTokenHash.sha256(second));
        assertThatThrownBy(() -> service.rotate(first))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    private static final class InMemoryRefreshTokenRepository implements RefreshTokenRepository {
        private StoredRefreshToken active;

        @Override
        public Optional<StoredRefreshToken> findActiveByHash(String tokenHash) {
            return active != null && active.tokenHash().equals(tokenHash) ? Optional.of(active) : Optional.empty();
        }

        @Override
        public Optional<StoredRefreshToken> findActiveByUserId(long userId) {
            return active != null && active.userId() == userId ? Optional.of(active) : Optional.empty();
        }

        @Override
        public void replaceActive(StoredRefreshToken token) {
            active = token;
        }

        @Override
        public boolean rotateActive(String expectedHash, StoredRefreshToken replacement, Instant rotatedAt) {
            if (active == null || !active.tokenHash().equals(expectedHash)) return false;
            active = replacement;
            return true;
        }

        @Override
        public void revokeByUserId(long userId, Instant revokedAt) {
            if (active != null && active.userId() == userId) active = null;
        }
    }

    private static final class SequentialOpaqueTokenGenerator implements OpaqueTokenGenerator {
        private int sequence;

        @Override
        public String generate() {
            return "opaque-refresh-token-" + ++sequence;
        }
    }
}
