package com.home.application.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.user.port.UserRepository;
import com.home.domain.user.OAuthIdentityKey;
import com.home.domain.user.OAuthProvider;
import com.home.domain.user.UserProfile;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CurrentUserQueryServiceTest {
    @Test
    void returnsCurrentUserAndHidesMissingUserBehindDomainException() {
        OAuthLoginResult user = new OAuthLoginResult(42L, OAuthProvider.GOOGLE, new UserProfile("사용자", null, null));
        var service = new CurrentUserQueryService(new QueryOnlyRepository(user));
        assertThat(service.find(42L)).isEqualTo(user);
        assertThatThrownBy(() -> service.find(7L)).isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> new CurrentUserQueryService(null)).isInstanceOf(NullPointerException.class);
    }

    private record QueryOnlyRepository(OAuthLoginResult user) implements UserRepository {
        @Override
        public Optional<OAuthLoginResult> findByUserId(long userId) {
            return user.userId() == userId ? Optional.of(user) : Optional.empty();
        }

        @Override
        public Optional<OAuthLoginResult> findByIdentity(OAuthIdentityKey identity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OAuthLoginResult create(OAuthIdentityKey identity, UserProfile profile, Instant loginAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OAuthLoginResult updateProfile(OAuthIdentityKey identity, UserProfile profile, Instant loginAt) {
            throw new UnsupportedOperationException();
        }
    }
}
