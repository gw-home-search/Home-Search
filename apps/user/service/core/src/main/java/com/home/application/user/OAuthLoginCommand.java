package com.home.application.user;

import com.home.domain.user.OAuthProvider;
import com.home.domain.user.UserProfile;
import java.time.Instant;

public record OAuthLoginCommand(OAuthProvider provider, String providerSubject, UserProfile profile, Instant loginAt) {
    public OAuthLoginCommand {
        if (provider == null || profile == null || loginAt == null) throw new IllegalArgumentException("login fields are required");
    }
}
