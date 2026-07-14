package com.home.application.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.auth.port.OpaqueTokenGenerator;
import com.home.application.auth.port.RefreshTokenRepository;
import com.home.domain.user.token.ActiveRefreshToken;
import com.home.domain.user.token.InvalidRefreshTokenException;
import com.home.domain.user.token.RefreshTokenHash;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RefreshTokenServiceTest {

    @Test
    void rotatesAtomicallyAndReturnsTheOwningUser() {
        var repository = new MemoryRepository();
        var service = new RefreshTokenService(
                repository, new SequenceGenerator(), () -> Instant.parse("2026-07-13T00:00:00Z"), Duration.ofDays(30));

        IssuedRefreshToken issued = service.issue(42L);
        RotatedRefreshToken rotated = service.rotate(issued.rawToken());

        assertThat(rotated.userId()).isEqualTo(42L);
        assertThat(rotated.rawToken()).isNotEqualTo(issued.rawToken());
        assertThat(repository.active.tokenHash()).isEqualTo(RefreshTokenHash.sha256(rotated.rawToken()));
        assertThatThrownBy(() -> service.rotate(issued.rawToken())).isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void issuesRevokesAndRejectsInvalidInputs() {
        var repository = new MemoryRepository();
        var service = new RefreshTokenService(
                repository, new SequenceGenerator(), () -> Instant.parse("2026-07-13T00:00:00Z"), Duration.ofDays(30));

        IssuedRefreshToken issued = service.issue(7L);
        assertThat(issued.userId()).isEqualTo(7L);
        service.revoke(issued.rawToken());
        assertThat(repository.active).isNull();
        service.revoke(null);

        assertThatThrownBy(() -> service.issue(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefreshTokenService(
                        repository, new SequenceGenerator(), () -> Instant.EPOCH, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsExpiredAndLostRotationRace() {
        var repository = new MemoryRepository();
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        var service = new RefreshTokenService(repository, new SequenceGenerator(), () -> now, Duration.ofDays(30));
        repository.active = new ActiveRefreshToken(7L, RefreshTokenHash.sha256("expired"), now.minusSeconds(10), now);
        assertThatThrownBy(() -> service.rotate("expired")).isInstanceOf(InvalidRefreshTokenException.class);

        IssuedRefreshToken issued = service.issue(7L);
        repository.failRotation = true;
        assertThatThrownBy(() -> service.rotate(issued.rawToken())).isInstanceOf(InvalidRefreshTokenException.class);
    }

    private static final class MemoryRepository implements RefreshTokenRepository {
        private ActiveRefreshToken active;
        private boolean failRotation;

        @Override
        public void replaceActive(ActiveRefreshToken token) {
            active = token;
        }

        @Override
        public Optional<ActiveRefreshToken> findActiveByHash(String hash) {
            return active != null && active.tokenHash().equals(hash) ? Optional.of(active) : Optional.empty();
        }

        @Override
        public boolean rotateActive(String expectedHash, ActiveRefreshToken replacement, Instant now) {
            if (failRotation) return false;
            if (active == null || !active.tokenHash().equals(expectedHash) || active.isExpiredAt(now)) return false;
            active = replacement;
            return true;
        }

        @Override
        public void revokeByHash(String hash, Instant now) {
            if (active != null && active.tokenHash().equals(hash)) active = null;
        }
    }

    private static final class SequenceGenerator implements OpaqueTokenGenerator {
        private int value;

        @Override
        public String generate() {
            return "long-enough-opaque-token-" + ++value;
        }
    }
}
