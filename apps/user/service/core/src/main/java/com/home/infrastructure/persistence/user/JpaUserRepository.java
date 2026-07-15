package com.home.infrastructure.persistence.user;

import com.home.application.user.OAuthLoginResult;
import com.home.application.user.port.UserRepository;
import com.home.domain.user.OAuthIdentityKey;
import com.home.domain.user.OAuthProvider;
import com.home.domain.user.UserProfile;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaUserRepository implements UserRepository {
    private final SpringDataUserAccountRepository accounts;
    private final SpringDataOAuthIdentityRepository identities;

    public JpaUserRepository(SpringDataUserAccountRepository accounts, SpringDataOAuthIdentityRepository identities) {
        this.accounts = accounts;
        this.identities = identities;
    }

    @Override
    public Optional<OAuthLoginResult> findByIdentity(OAuthIdentityKey key) {
        return identities
                .findByProviderAndProviderSubject(key.provider().name(), key.providerSubject())
                .flatMap(this::result);
    }

    @Override
    @Transactional
    public OAuthLoginResult create(OAuthIdentityKey key, UserProfile profile, Instant now) {
        var account = accounts.save(
                new UserAccountJpaEntity(profile.displayName(), profile.email(), profile.profileImage(), now));
        identities.save(new OAuthIdentityJpaEntity(account.id(), key.provider().name(), key.providerSubject(), now));
        return result(account, key.provider());
    }

    @Override
    @Transactional
    public OAuthLoginResult updateProfile(OAuthIdentityKey key, UserProfile profile, Instant now) {
        var identity = identities
                .findByProviderAndProviderSubject(key.provider().name(), key.providerSubject())
                .orElseThrow();
        var account = accounts.findById(identity.userId()).orElseThrow();
        account.update(profile.displayName(), profile.email(), profile.profileImage(), now);
        identity.loginAt(now);
        return result(account, key.provider());
    }

    @Override
    public Optional<OAuthLoginResult> findByUserId(long userId) {
        return identities.findByUserId(userId).flatMap(this::result);
    }

    private Optional<OAuthLoginResult> result(OAuthIdentityJpaEntity identity) {
        return accounts.findById(identity.userId())
                .map(account -> result(account, OAuthProvider.valueOf(identity.provider())));
    }

    private OAuthLoginResult result(UserAccountJpaEntity account, OAuthProvider provider) {
        return new OAuthLoginResult(
                account.id(),
                provider,
                new UserProfile(account.displayName(), account.email(), account.profileImage()));
    }
}
