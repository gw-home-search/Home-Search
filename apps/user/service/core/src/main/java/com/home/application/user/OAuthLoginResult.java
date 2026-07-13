package com.home.application.user;

import com.home.domain.user.OAuthProvider;
import com.home.domain.user.UserProfile;

public record OAuthLoginResult(long userId, OAuthProvider provider, UserProfile profile) {
    public OAuthLoginResult {
        if (userId <= 0 || provider == null || profile == null) throw new IllegalArgumentException("user result fields are required");
    }
}
