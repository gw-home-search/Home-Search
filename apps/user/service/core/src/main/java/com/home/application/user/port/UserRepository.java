package com.home.application.user.port;

import com.home.application.user.OAuthLoginResult;
import com.home.domain.user.OAuthIdentityKey;
import com.home.domain.user.UserProfile;
import java.time.Instant;
import java.util.Optional;

public interface UserRepository {
    Optional<OAuthLoginResult> findByIdentity(OAuthIdentityKey identity);

    OAuthLoginResult create(OAuthIdentityKey identity, UserProfile profile, Instant loginAt);

    OAuthLoginResult updateProfile(OAuthIdentityKey identity, UserProfile profile, Instant loginAt);

    Optional<OAuthLoginResult> findByUserId(long userId);
}
