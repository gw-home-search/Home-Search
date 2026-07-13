package com.home.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.domain.user.token.ActiveRefreshToken;
import com.home.domain.user.token.InvalidRefreshTokenException;
import com.home.domain.user.token.RefreshTokenHash;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UserDomainTest {
    @Test
    void normalizesIdentityAndMergesOnlyProvidedProfileValues() {
        var identity = new OAuthIdentityKey(OAuthProvider.KAKAO, " subject ");
        var current = new UserProfile("기존", "old@example.com", "image");
        assertThat(identity.providerSubject()).isEqualTo("subject");
        assertThat(current.merge(new UserProfile(" 신규 ", "null", " ")))
                .isEqualTo(new UserProfile("신규", "old@example.com", "image"));
        assertThat(new UserProfile(null, null, null).forNewUser().displayName()).isEqualTo("홈서치 사용자");
    }

    @Test
    void rejectsInvalidIdentityProfileAndRefreshState() {
        assertThatThrownBy(() -> new OAuthIdentityKey(null, "subject")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OAuthIdentityKey(OAuthProvider.GOOGLE, "null")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserProfile("x".repeat(101), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserProfile("name", "x".repeat(321), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RefreshTokenHash.sha256(" ")).isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> new ActiveRefreshToken(0, "0".repeat(64), Instant.EPOCH, Instant.EPOCH.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesStableKoreanMetadataAndExpiryBoundary() {
        assertThat(OAuthProvider.GOOGLE.titleKo()).isEqualTo("구글");
        assertThat(OAuthProvider.GOOGLE.descriptionKo()).isNotBlank();
        assertThat(UserRole.USER.titleKo()).isEqualTo("일반 사용자");
        assertThat(UserRole.USER.descriptionKo()).isNotBlank();
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        var token = new ActiveRefreshToken(1, RefreshTokenHash.sha256("raw"), now.minusSeconds(1), now);
        assertThat(token.isExpiredAt(now)).isTrue();
    }
}
