package com.home.application.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.user.port.IdentityLock;
import com.home.application.user.port.UserRepository;
import com.home.domain.user.OAuthIdentityKey;
import com.home.domain.user.OAuthProvider;
import com.home.domain.user.UserProfile;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OAuthLoginServiceTest {

    @Test
    void compositeIdentityOwnsLoginAndMissingProfileValuesArePreserved() {
        var repository = new MemoryUserRepository();
        var service = new OAuthLoginService(repository, IdentityLock.noop());
        Instant firstLogin = Instant.parse("2026-07-13T00:00:00Z");

        OAuthLoginResult created = service.login(new OAuthLoginCommand(
                OAuthProvider.GOOGLE, " google-subject ",
                new UserProfile("첫 이름", "first@example.com", "https://example.com/first.png"), firstLogin));
        OAuthLoginResult loggedInAgain = service.login(new OAuthLoginCommand(
                OAuthProvider.GOOGLE, "google-subject",
                new UserProfile("새 이름", null, null), firstLogin.plusSeconds(60)));
        OAuthLoginResult otherProvider = service.login(new OAuthLoginCommand(
                OAuthProvider.NAVER, "google-subject",
                new UserProfile("다른 사용자", "first@example.com", null), firstLogin.plusSeconds(120)));

        assertThat(loggedInAgain.userId()).isEqualTo(created.userId());
        assertThat(loggedInAgain.profile()).isEqualTo(
                new UserProfile("새 이름", "first@example.com", "https://example.com/first.png"));
        assertThat(otherProvider.userId()).isNotEqualTo(created.userId());
    }

    private static final class MemoryUserRepository implements UserRepository {
        private final Map<OAuthIdentityKey, OAuthLoginResult> users = new HashMap<>();
        private long sequence;

        @Override
        public Optional<OAuthLoginResult> findByIdentity(OAuthIdentityKey identity) {
            return Optional.ofNullable(users.get(identity));
        }

        @Override
        public OAuthLoginResult create(OAuthIdentityKey identity, UserProfile profile, Instant loginAt) {
            var result = new OAuthLoginResult(++sequence, identity.provider(), profile);
            users.put(identity, result);
            return result;
        }

        @Override
        public OAuthLoginResult updateProfile(OAuthIdentityKey identity, UserProfile profile, Instant loginAt) {
            var current = users.get(identity);
            var result = new OAuthLoginResult(current.userId(), identity.provider(), profile);
            users.put(identity, result);
            return result;
        }

        @Override
        public Optional<OAuthLoginResult> findByUserId(long userId) {
            return users.values().stream().filter(user -> user.userId() == userId).findFirst();
        }
    }
}
